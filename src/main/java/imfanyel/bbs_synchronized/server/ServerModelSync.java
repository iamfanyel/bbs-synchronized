package imfanyel.bbs_synchronized.server;

import imfanyel.bbs_synchronized.BBSSynchronized;
import imfanyel.bbs_synchronized.network.SyncPackets;
import imfanyel.bbs_synchronized.network.SyncPayload;
import imfanyel.bbs_synchronized.sync.HashCache;
import imfanyel.bbs_synchronized.sync.ManifestEntry;
import imfanyel.bbs_synchronized.sync.SyncExecutors;
import imfanyel.bbs_synchronized.sync.SyncPaths;
import imfanyel.bbs_synchronized.sync.TransferSession;
import mchorse.bbs_mod.BBSMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The server half of the synchronization pipeline.
 *
 * <p>Everything that touches the disk (hashing, reading, writing) runs on
 * background executors; the ordered single-thread IO executor guarantees that
 * upload chunks are written in the order they arrived on the network thread.
 * Upload sessions are tracked per player and swept when they go stale, so a
 * crashed or disconnected client never leaves the pipeline stuck.</p>
 */
public class ServerModelSync
{
    /** Parallel workers for hashing and download streaming */
    private static ExecutorService workers;

    /** Ordered writer used by upload sessions */
    private static ExecutorService io;

    private static final HashCache hashes = new HashCache();

    /** Active upload sessions, keyed by player UUID and transfer id */
    private static final Map<UUID, Map<Integer, TransferSession>> uploads = new ConcurrentHashMap<>();

    /** Files actually written per player during the current upload batch */
    private static final Map<UUID, AtomicInteger> uploadedCounts = new ConcurrentHashMap<>();

    /** Players that currently have a download batch being streamed to them */
    private static final Set<UUID> streaming = ConcurrentHashMap.newKeySet();

    /** Partially received (chunked) file request lists, keyed by player */
    private static final Map<UUID, List<String>> pendingRequests = new ConcurrentHashMap<>();

    /** Serializes chunked list sends so two manifests can't interleave */
    private static final Object sendLock = new Object();

    private static final long UPLOAD_TIMEOUT_MS = 60_000;
    private static final int SWEEP_INTERVAL_TICKS = 100;
    private static final int PRUNE_INTERVAL_SWEEPS = 60;
    private static final int MAX_UPLOAD_SESSIONS_PER_PLAYER = 4;

    private static int sweepCounter;
    private static int pruneCounter;

    /**
     * The server-side store of distributed models. It's deliberately separate
     * from the BBS assets folder so that the host of an integrated server
     * doesn't share storage with the server — uploads land here, and the host
     * downloads them into their own assets like every other player.
     */
    public static File getModelsFolder()
    {
        File folder = BBSMod.getGamePath("config/bbs/sync_models");

        folder.mkdirs();

        return folder;
    }

    private static File getUploadTmpFolder()
    {
        File folder = BBSMod.getGamePath("config/bbs/sync_uploads");

        folder.mkdirs();

        return folder;
    }

    public static void init()
    {
        ServerPlayNetworking.registerGlobalReceiver(SyncPayload.ID, (payload, context) ->
        {
            MinecraftServer server = context.server();
            ServerPlayerEntity player = context.player();
            PacketByteBuf buf = SyncPackets.wrap(payload.data());

            switch (payload.channel())
            {
                case SyncPackets.CH_HELLO -> sendManifest(player, SyncPackets.REASON_JOIN);
                case SyncPackets.CH_REQUEST_FILES -> handleRequestFiles(server, player, buf);
                case SyncPackets.CH_UP_BEGIN -> handleUploadBegin(player, buf);
                case SyncPackets.CH_UP_DATA -> handleUploadData(player, buf);
                case SyncPackets.CH_UP_END -> handleUploadEnd(player, buf);
                case SyncPackets.CH_UP_DONE -> handleUploadDone(server, player);
                default -> {}
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register((server) ->
        {
            workers = SyncExecutors.workers("BBS-Sync-Worker");
            io = SyncExecutors.orderedIo("BBS-Sync-IO");

            File store = getModelsFolder();

            /* One-time migration for dedicated servers that served models
             * straight from the BBS assets folder before the store existed */
            if (server.isDedicated() && isEmpty(store))
            {
                File legacy = BBSMod.getAssetsPath("models");

                if (legacy.isDirectory())
                {
                    submit(workers, () -> migrateLegacyModels(legacy, store));
                }
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register((server) ->
        {
            if (workers != null) workers.shutdownNow();
            if (io != null) io.shutdownNow();

            workers = null;
            io = null;

            for (Map<Integer, TransferSession> sessions : uploads.values())
            {
                sessions.values().forEach(TransferSession::abort);
            }

            uploads.clear();
            uploadedCounts.clear();
            pendingRequests.clear();
            streaming.clear();
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
        {
            UUID uuid = handler.player.getUuid();
            Map<Integer, TransferSession> sessions = uploads.remove(uuid);

            if (sessions != null)
            {
                submit(io, () -> sessions.values().forEach(TransferSession::abort));
            }

            uploadedCounts.remove(uuid);
            pendingRequests.remove(uuid);
            streaming.remove(uuid);
        });

        /* Sweep stale upload sessions every 5 seconds */
        ServerTickEvents.END_SERVER_TICK.register((server) ->
        {
            if (++sweepCounter < SWEEP_INTERVAL_TICKS)
            {
                return;
            }

            sweepCounter = 0;

            long now = System.currentTimeMillis();

            for (Map<Integer, TransferSession> sessions : uploads.values())
            {
                Iterator<TransferSession> it = sessions.values().iterator();

                while (it.hasNext())
                {
                    TransferSession session = it.next();

                    if (now - session.lastActivity > UPLOAD_TIMEOUT_MS)
                    {
                        it.remove();
                        submit(io, session::abort);

                        BBSSynchronized.LOGGER.warn("Upload of \"{}\" timed out and was discarded", session.path);
                    }
                }
            }

            /* Pruning stats every cached file, so do it rarely (~5 minutes) */
            if (++pruneCounter >= PRUNE_INTERVAL_SWEEPS)
            {
                pruneCounter = 0;
                submit(workers, hashes::prune);
            }
        });
    }

    private static boolean isEmpty(File folder)
    {
        String[] entries = folder.list();

        return entries == null || entries.length == 0;
    }

    /**
     * Copy syncable files from the legacy location ({@code config/bbs/assets/
     * models}) into the sync store. Non-destructive: the originals stay put.
     */
    private static void migrateLegacyModels(File legacy, File store)
    {
        int copied = 0;

        for (ManifestEntry entry : new HashCache().buildManifest(legacy))
        {
            File source = SyncPaths.resolve(legacy, entry.path);
            File target = SyncPaths.resolve(store, entry.path);

            if (source == null || target == null || target.exists())
            {
                continue;
            }

            try
            {
                target.getParentFile().mkdirs();
                Files.copy(source.toPath(), target.toPath());
                copied += 1;
            }
            catch (Exception e)
            {
                BBSSynchronized.LOGGER.error("Couldn't migrate \"{}\" into the sync store", entry.path, e);
            }
        }

        if (copied > 0)
        {
            BBSSynchronized.LOGGER.info("Migrated {} model file(s) from config/bbs/assets/models into config/bbs/sync_models — the sync store is the distribution folder from now on", copied);
        }
    }

    /** Submit a task, silently dropping it when the server is shutting down */
    private static void submit(ExecutorService executor, Runnable task)
    {
        if (executor == null || executor.isShutdown())
        {
            return;
        }

        try
        {
            executor.submit(task);
        }
        catch (RejectedExecutionException e)
        {}
    }

    public static boolean isClientSynchronized(ServerPlayerEntity player)
    {
        return ServerPlayNetworking.canSend(player, SyncPayload.ID);
    }

    /* Manifest distribution */

    /**
     * Build the manifest asynchronously and send it to the given player. The
     * client diffs it against its local files and requests what it's missing.
     */
    public static void sendManifest(ServerPlayerEntity player, byte reason)
    {
        submit(workers, () ->
        {
            try
            {
                List<ManifestEntry> manifest = hashes.buildManifest(getModelsFolder());

                BBSSynchronized.LOGGER.info("Sending model manifest ({} file(s)) to {}", manifest.size(), player.getGameProfile().getName());

                synchronized (sendLock)
                {
                    SyncPackets.chunkManifest(manifest, SyncPackets.SAFE_S2C_BYTES, (slice, last) ->
                        ServerPlayNetworking.send(player, SyncPackets.make(SyncPackets.CH_MANIFEST, (buf) ->
                        {
                            buf.writeByte(reason);
                            buf.writeBoolean(last);
                            SyncPackets.writeManifest(buf, slice);
                        })));
                }
            }
            catch (Exception e)
            {
                BBSSynchronized.LOGGER.error("Failed to send model manifest to {}", player.getGameProfile().getName(), e);
            }
        });
    }

    /**
     * Send the current manifest together with an upload request, in response
     * to {@code /bbs model upload}. The client diffs its local models against
     * it and streams back what the server is missing.
     */
    public static void requestUpload(ServerPlayerEntity player, boolean force)
    {
        submit(workers, () ->
        {
            try
            {
                List<ManifestEntry> manifest = hashes.buildManifest(getModelsFolder());

                synchronized (sendLock)
                {
                    SyncPackets.chunkManifest(manifest, SyncPackets.SAFE_S2C_BYTES, (slice, last) ->
                        ServerPlayNetworking.send(player, SyncPackets.make(SyncPackets.CH_UPLOAD_GO, (buf) ->
                        {
                            buf.writeBoolean(force);
                            buf.writeBoolean(last);
                            SyncPackets.writeManifest(buf, slice);
                        })));
                }
            }
            catch (Exception e)
            {
                BBSSynchronized.LOGGER.error("Failed to request upload from {}", player.getGameProfile().getName(), e);
            }
        });
    }

    /* Download streaming (server -> client) */

    private static void handleRequestFiles(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf)
    {
        /* Requests arrive as chunked slices (32 KiB serverbound packet limit);
         * accumulate them until the slice flagged as last. Payload handlers
         * run on the server thread, so plain lists are safe here. */
        boolean last = buf.readBoolean();
        int count = Math.min(buf.readInt(), SyncPackets.MAX_FILES);
        List<String> pending = pendingRequests.computeIfAbsent(player.getUuid(), (k) -> new ArrayList<>());

        for (int i = 0; i < count && pending.size() < SyncPackets.MAX_FILES; i++)
        {
            pending.add(buf.readString());
        }

        if (!last)
        {
            return;
        }

        pendingRequests.remove(player.getUuid());

        List<String> paths = new ArrayList<>(new LinkedHashSet<>(pending));

        BBSSynchronized.LOGGER.info("{} requested {} model file(s)", player.getGameProfile().getName(), paths.size());

        if (paths.isEmpty() || !streaming.add(player.getUuid()))
        {
            /* Nothing to send, or a batch is already being streamed */
            return;
        }

        submit(workers, () -> streamFiles(player, paths));
    }

    private static void streamFiles(ServerPlayerEntity player, List<String> paths)
    {
        File root = getModelsFolder();
        int sent = 0;
        int tid = 0;

        try
        {
            for (String path : paths)
            {
                if (player.isDisconnected())
                {
                    return;
                }

                File file = SyncPaths.resolve(root, path);

                if (file == null || !file.isFile() || file.length() > SyncPackets.MAX_FILE_SIZE)
                {
                    continue;
                }

                String sha1 = hashes.hash(file);

                if (sha1 == null)
                {
                    continue;
                }

                tid += 1;

                final int fileTid = tid;

                ServerPlayNetworking.send(player, SyncPackets.make(SyncPackets.CH_FILE_BEGIN, (begin) ->
                {
                    begin.writeInt(fileTid);
                    begin.writeString(path);
                    begin.writeLong(file.length());
                    begin.writeString(sha1);
                }));

                try (InputStream in = new FileInputStream(file))
                {
                    byte[] buffer = new byte[SyncPackets.CHUNK_SIZE];
                    int read;
                    int chunks = 0;

                    while ((read = in.read(buffer)) > 0)
                    {
                        if (player.isDisconnected())
                        {
                            return;
                        }

                        final int chunkLength = read;

                        ServerPlayNetworking.send(player, SyncPackets.make(SyncPackets.CH_FILE_DATA, (chunk) ->
                        {
                            chunk.writeInt(fileTid);
                            chunk.writeVarInt(chunkLength);
                            chunk.writeBytes(buffer, 0, chunkLength);
                        }));

                        /* Gentle pacing: ~1.7 MB bursts, then breathe, so big
                         * transfers never starve the connection */
                        if (++chunks % 64 == 0)
                        {
                            Thread.sleep(15);
                        }
                    }
                }

                ServerPlayNetworking.send(player, SyncPackets.make(SyncPackets.CH_FILE_END, (end) -> end.writeInt(fileTid)));

                sent += 1;
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();

            return;
        }
        catch (Exception e)
        {
            BBSSynchronized.LOGGER.error("Failed streaming model files to {}", player.getGameProfile().getName(), e);
        }
        finally
        {
            streaming.remove(player.getUuid());
        }

        ServerPlayNetworking.send(player, SyncPackets.make(SyncPackets.CH_BATCH_DONE, null));

        if (sent > 0)
        {
            BBSSynchronized.LOGGER.info("Streamed {} model file(s) to {}", sent, player.getGameProfile().getName());
        }
    }

    /* Upload receiving (client -> server) */

    private static void handleUploadBegin(ServerPlayerEntity player, PacketByteBuf buf)
    {
        int tid = buf.readInt();
        String path = buf.readString();
        long size = buf.readLong();
        String sha1 = buf.readString();
        boolean force = buf.readBoolean();

        submit(io, () ->
        {
            Map<Integer, TransferSession> sessions = uploads.computeIfAbsent(player.getUuid(), (k) -> new ConcurrentHashMap<>());

            if (size < 0 || size > SyncPackets.MAX_FILE_SIZE
                || sessions.size() >= MAX_UPLOAD_SESSIONS_PER_PLAYER
                || SyncPaths.resolve(getModelsFolder(), path) == null)
            {
                sendAck(player, tid, SyncPackets.ACK_FAILED, path);

                return;
            }

            try
            {
                File tmp = new File(getUploadTmpFolder(), player.getUuid() + "-" + tid + ".tmp");
                TransferSession session = new TransferSession(path, size, sha1, tmp);

                session.force = force;
                sessions.put(tid, session);
            }
            catch (Exception e)
            {
                BBSSynchronized.LOGGER.error("Couldn't open upload session for \"{}\"", path, e);
                sendAck(player, tid, SyncPackets.ACK_FAILED, path);
            }
        });
    }

    private static void handleUploadData(ServerPlayerEntity player, PacketByteBuf buf)
    {
        int tid = buf.readInt();
        byte[] data = buf.readByteArray();

        submit(io, () ->
        {
            Map<Integer, TransferSession> sessions = uploads.get(player.getUuid());
            TransferSession session = sessions == null ? null : sessions.get(tid);

            if (session == null)
            {
                return;
            }

            if (!session.write(data))
            {
                sessions.remove(tid);
                session.abort();
                sendAck(player, tid, SyncPackets.ACK_FAILED, session.path);
            }
        });
    }

    private static void handleUploadEnd(ServerPlayerEntity player, PacketByteBuf buf)
    {
        int tid = buf.readInt();

        submit(io, () ->
        {
            Map<Integer, TransferSession> sessions = uploads.get(player.getUuid());
            TransferSession session = sessions == null ? null : sessions.remove(tid);

            if (session == null)
            {
                return;
            }

            byte status = finishUpload(session);

            sendAck(player, tid, status == SyncPackets.ACK_WRITTEN ? SyncPackets.ACK_OK : status, session.path);

            if (status == SyncPackets.ACK_WRITTEN)
            {
                uploadedCounts.computeIfAbsent(player.getUuid(), (k) -> new AtomicInteger()).incrementAndGet();
                BBSSynchronized.LOGGER.info("{} uploaded model file \"{}\"", player.getGameProfile().getName(), session.path);
            }
        });
    }

    /**
     * Verify a completed upload and move it into the sync store. Existing
     * store files are only replaced when the upload was forced
     * ({@code /bbs model upload --force}).
     */
    private static byte finishUpload(TransferSession session)
    {
        if (!session.verify())
        {
            return SyncPackets.ACK_FAILED;
        }

        File target = SyncPaths.resolve(getModelsFolder(), session.path);

        if (target == null)
        {
            session.discard();

            return SyncPackets.ACK_FAILED;
        }

        if (target.exists() && !session.force)
        {
            String existing = hashes.hash(target);

            session.discard();

            /* Store files are only overwritten by forced uploads: an
             * identical file is fine, a different one is skipped */
            return session.sha1.equals(existing) ? SyncPackets.ACK_OK : SyncPackets.ACK_SKIPPED;
        }

        if (!session.moveTo(target))
        {
            return SyncPackets.ACK_FAILED;
        }

        hashes.invalidate(target);

        return SyncPackets.ACK_WRITTEN;
    }

    private static void handleUploadDone(MinecraftServer server, ServerPlayerEntity player)
    {
        /* The batch may still be in the IO queue behind this packet, so
         * resolve the authoritative count on the IO thread */
        submit(io, () ->
        {
            AtomicInteger counter = uploadedCounts.remove(player.getUuid());
            int uploaded = counter == null ? 0 : counter.get();

            if (uploaded <= 0)
            {
                return;
            }

            server.execute(() ->
            {
                Text announcement = Text.translatable("bbs_synchronized.prefix").formatted(Formatting.AQUA)
                    .append(Text.translatable("bbs_synchronized.broadcast.uploaded",
                        player.getGameProfile().getName(),
                        uploaded,
                        Text.literal("/bbs model download").formatted(Formatting.WHITE)
                    ).formatted(Formatting.GRAY));

                for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList())
                {
                    other.sendMessage(announcement);
                }
            });
        });
    }

    private static void sendAck(ServerPlayerEntity player, int tid, byte status, String path)
    {
        if (player.isDisconnected())
        {
            return;
        }

        ServerPlayNetworking.send(player, SyncPackets.make(SyncPackets.CH_UPLOAD_ACK, (buf) ->
        {
            buf.writeInt(tid);
            buf.writeByte(status);
            buf.writeString(path);
        }));
    }
}
