package imfanyel.bbs_synchronized.client;

import imfanyel.bbs_synchronized.BBSSynchronized;
import imfanyel.bbs_synchronized.network.SyncPackets;
import imfanyel.bbs_synchronized.sync.HashCache;
import imfanyel.bbs_synchronized.sync.ManifestEntry;
import imfanyel.bbs_synchronized.sync.SyncExecutors;
import imfanyel.bbs_synchronized.sync.SyncPaths;
import imfanyel.bbs_synchronized.sync.TransferSession;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSResources;
import mchorse.bbs_mod.resources.Link;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The client half of the synchronization pipeline.
 *
 * <p>On join the client greets the server, receives the model manifest, diffs
 * it against the local {@code config/bbs/assets/models} folder in a worker
 * thread and only requests files that are missing or different. Downloaded
 * files are written to a temporary folder, hash-verified and moved into place
 * by an ordered IO thread; once the batch completes, textures, models and the
 * form list are reloaded on the render thread.</p>
 */
public class ClientModelSync
{
    private static ExecutorService workers;
    private static ExecutorService io;

    private static final HashCache hashes = new HashCache();

    /* Download state */
    private static final Map<Integer, TransferSession> downloads = new ConcurrentHashMap<>();
    private static volatile boolean syncing;
    private static volatile boolean uploading;
    private static volatile long lastDownloadActivity;
    private static final List<String> downloadedPaths = new ArrayList<>();
    private static final AtomicInteger downloadedCount = new AtomicInteger();
    private static volatile int expectedCount;

    /* Join handshake */
    private static boolean sentHello;
    private static int joinTicks;

    /** How long to keep retrying the join handshake before assuming the
     *  server doesn't have the addon (in ticks) */
    private static final int HELLO_GIVE_UP_TICKS = 600;

    /* Accumulators for chunked list packets (handled on the network thread,
     * which is single-threaded per connection) */
    private static final List<ManifestEntry> manifestBuffer = new ArrayList<>();
    private static final List<ManifestEntry> uploadGoBuffer = new ArrayList<>();

    private static final long DOWNLOAD_TIMEOUT_MS = 30_000;

    private static File getModelsFolder()
    {
        File folder = BBSMod.getAssetsPath("models");

        folder.mkdirs();

        return folder;
    }

    private static File getDownloadTmpFolder()
    {
        File folder = BBSMod.getGamePath("config/bbs/sync_downloads");

        folder.mkdirs();

        return folder;
    }

    public static void init()
    {
        workers = SyncExecutors.workers("BBS-Sync-Client-Worker");
        io = SyncExecutors.orderedIo("BBS-Sync-Client-IO");

        ClientPlayNetworking.registerGlobalReceiver(SyncPackets.MANIFEST, (client, handler, buf, responder) ->
        {
            byte reason = buf.readByte();
            boolean last = buf.readBoolean();

            if (manifestBuffer.size() < SyncPackets.MAX_FILES)
            {
                manifestBuffer.addAll(SyncPackets.readManifest(buf));
            }

            if (!last)
            {
                return;
            }

            List<ManifestEntry> manifest = new ArrayList<>(manifestBuffer);

            manifestBuffer.clear();
            BBSSynchronized.LOGGER.info("Received server model manifest with {} file(s)", manifest.size());
            workers.submit(() -> handleManifest(reason, manifest));
        });

        ClientPlayNetworking.registerGlobalReceiver(SyncPackets.FILE_BEGIN, (client, handler, buf, responder) ->
        {
            int tid = buf.readInt();
            String path = buf.readString();
            long size = buf.readLong();
            String sha1 = buf.readString();

            io.submit(() -> beginDownload(tid, path, size, sha1));
        });

        ClientPlayNetworking.registerGlobalReceiver(SyncPackets.FILE_DATA, (client, handler, buf, responder) ->
        {
            int tid = buf.readInt();
            byte[] data = buf.readByteArray();

            io.submit(() -> downloadData(tid, data));
        });

        ClientPlayNetworking.registerGlobalReceiver(SyncPackets.FILE_END, (client, handler, buf, responder) ->
        {
            int tid = buf.readInt();

            io.submit(() -> endDownload(tid));
        });

        ClientPlayNetworking.registerGlobalReceiver(SyncPackets.BATCH_DONE, (client, handler, buf, responder) -> io.submit(ClientModelSync::finishBatch));

        ClientPlayNetworking.registerGlobalReceiver(SyncPackets.UPLOAD_GO, (client, handler, buf, responder) ->
        {
            boolean last = buf.readBoolean();

            if (uploadGoBuffer.size() < SyncPackets.MAX_FILES)
            {
                uploadGoBuffer.addAll(SyncPackets.readManifest(buf));
            }

            if (!last)
            {
                return;
            }

            List<ManifestEntry> manifest = new ArrayList<>(uploadGoBuffer);

            uploadGoBuffer.clear();
            workers.submit(() -> handleUploadRequest(manifest));
        });

        ClientPlayNetworking.registerGlobalReceiver(SyncPackets.UPLOAD_ACK, (client, handler, buf, responder) ->
        {
            buf.readInt();

            byte status = buf.readByte();
            String path = buf.readString();

            if (status == SyncPackets.ACK_SKIPPED)
            {
                message(Formatting.YELLOW, "bbs_synchronized.upload.skipped", path);
            }
            else if (status == SyncPackets.ACK_FAILED)
            {
                message(Formatting.RED, "bbs_synchronized.upload.rejected", path);
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
        {
            sentHello = false;
            joinTicks = 0;
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());

        ClientTickEvents.END_CLIENT_TICK.register((client) ->
        {
            /* Delayed hello: wait for the server to announce its channels,
             * retrying every second until it does (or clearly never will).
             * Skipped when we ARE the (integrated) server — same files. */
            if (client.player != null && !sentHello)
            {
                joinTicks++;

                if (client.isIntegratedServerRunning() || joinTicks > HELLO_GIVE_UP_TICKS)
                {
                    sentHello = true;
                }
                else if (joinTicks >= 40 && joinTicks % 20 == 0 && ClientPlayNetworking.canSend(SyncPackets.HELLO))
                {
                    sentHello = true;

                    BBSSynchronized.LOGGER.info("Requesting model manifest from the server");
                    ClientPlayNetworking.send(SyncPackets.HELLO, PacketByteBufs.create());
                }
            }

            /* Recover gracefully when a download batch stalls */
            if (syncing && System.currentTimeMillis() - lastDownloadActivity > DOWNLOAD_TIMEOUT_MS)
            {
                io.submit(() ->
                {
                    if (!syncing)
                    {
                        return;
                    }

                    message(Formatting.RED, "bbs_synchronized.sync.timeout");
                    finishBatch();
                });
            }
        });
    }

    private static void reset()
    {
        for (TransferSession session : downloads.values())
        {
            io.submit(session::abort);
        }

        downloads.clear();
        manifestBuffer.clear();
        uploadGoBuffer.clear();

        if (syncing)
        {
            /* We stopped the watchdog when the batch started; don't leave it off */
            BBSResources.setupWatchdog();
        }

        syncing = false;
        uploading = false;
        sentHello = false;
        joinTicks = 0;

        synchronized (downloadedPaths)
        {
            downloadedPaths.clear();
        }
    }

    /* Downloading */

    /**
     * Atomically claim the sync state, so overlapping manifests (join +
     * reload spam) can't start two batches at once.
     */
    private static synchronized boolean tryBegin(boolean upload)
    {
        if (syncing || uploading)
        {
            return false;
        }

        if (upload)
        {
            uploading = true;
        }
        else
        {
            syncing = true;
            lastDownloadActivity = System.currentTimeMillis();
        }

        return true;
    }

    private static void handleManifest(byte reason, List<ManifestEntry> manifest)
    {
        if (!tryBegin(false))
        {
            if (reason == SyncPackets.REASON_RELOAD)
            {
                message(Formatting.YELLOW, "bbs_synchronized.sync.in_progress");
            }

            return;
        }

        File root = getModelsFolder();
        List<String> needed = new ArrayList<>();
        long totalBytes = 0;

        for (ManifestEntry entry : manifest)
        {
            /* Hashing a big folder can take a while — keep the stall
             * detector fed so it doesn't abort a diff in progress */
            lastDownloadActivity = System.currentTimeMillis();

            File local = SyncPaths.resolve(root, entry.path);

            if (local == null)
            {
                continue;
            }

            if (!local.isFile() || local.length() != entry.size || !entry.sha1.equals(hashes.hash(local)))
            {
                needed.add(entry.path);
                totalBytes += entry.size;
            }
        }

        if (needed.isEmpty())
        {
            syncing = false;

            if (reason == SyncPackets.REASON_RELOAD)
            {
                message(Formatting.GREEN, "bbs_synchronized.sync.up_to_date");
            }

            return;
        }

        expectedCount = needed.size();
        downloadedCount.set(0);

        synchronized (downloadedPaths)
        {
            downloadedPaths.clear();
        }

        /* Pause BBS's file watchdog while we write a batch of files, the
         * same way the built-in CDN sync does */
        BBSResources.stopWatchdog();

        message(Formatting.GRAY, "bbs_synchronized.sync.downloading", needed.size(), String.format("%.1f", totalBytes / (1024F * 1024F)));

        /* The request list can exceed the 32 KiB serverbound packet limit,
         * so it's sent as chunked slices */
        SyncPackets.chunkPaths(needed, SyncPackets.SAFE_C2S_BYTES, (slice, last) ->
        {
            PacketByteBuf buf = PacketByteBufs.create();

            buf.writeBoolean(last);
            buf.writeInt(slice.size());

            for (String path : slice)
            {
                buf.writeString(path);
            }

            ClientPlayNetworking.send(SyncPackets.REQUEST_FILES, buf);
        });
    }

    private static void beginDownload(int tid, String path, long size, String sha1)
    {
        lastDownloadActivity = System.currentTimeMillis();

        if (!syncing || size < 0 || size > SyncPackets.MAX_FILE_SIZE || SyncPaths.resolve(getModelsFolder(), path) == null)
        {
            return;
        }

        try
        {
            File tmp = new File(getDownloadTmpFolder(), "dl-" + tid + ".tmp");

            downloads.put(tid, new TransferSession(path, size, sha1, tmp));
        }
        catch (Exception e)
        {
            BBSSynchronized.LOGGER.error("Couldn't open download session for \"{}\"", path, e);
        }
    }

    private static void downloadData(int tid, byte[] data)
    {
        lastDownloadActivity = System.currentTimeMillis();

        TransferSession session = downloads.get(tid);

        if (session != null && !session.write(data))
        {
            downloads.remove(tid);
            session.abort();
        }
    }

    private static void endDownload(int tid)
    {
        lastDownloadActivity = System.currentTimeMillis();

        TransferSession session = downloads.remove(tid);

        if (session == null)
        {
            return;
        }

        File target = null;

        if (session.verify())
        {
            target = SyncPaths.resolve(getModelsFolder(), session.path);

            if (target == null)
            {
                session.discard();
            }
        }

        if (target != null && session.moveTo(target))
        {
            hashes.invalidate(target);

            synchronized (downloadedPaths)
            {
                downloadedPaths.add(session.path);
            }

            int count = downloadedCount.incrementAndGet();

            if (count % 25 == 0 && count < expectedCount)
            {
                message(Formatting.GRAY, "bbs_synchronized.sync.progress", count, expectedCount);
            }
        }
        else
        {
            message(Formatting.RED, "bbs_synchronized.sync.verify_failed", session.path);
        }
    }

    private static void finishBatch()
    {
        if (!syncing)
        {
            return;
        }

        syncing = false;

        /* Abort sessions that never received their FILE_END (interrupted stream) */
        for (TransferSession session : downloads.values())
        {
            session.abort();
        }

        downloads.clear();

        BBSResources.setupWatchdog();

        List<String> updated;

        synchronized (downloadedPaths)
        {
            updated = new ArrayList<>(downloadedPaths);
            downloadedPaths.clear();
        }

        if (updated.isEmpty())
        {
            return;
        }

        MinecraftClient.getInstance().execute(() ->
        {
            /* Refresh textures of updated files and reload all models so the
             * new assets show up immediately */
            for (String path : updated)
            {
                BBSModClient.getTextures().delete(Link.assets("models/" + path));
            }

            BBSModClient.getModels().reload();

            /* The form list is only updated through watchdog events, which
             * were paused while the files were written — rebuild it so the
             * new model folders show up in the form picker */
            BBSModClient.getFormCategories().setup();
        });

        message(Formatting.GREEN, "bbs_synchronized.sync.done", updated.size());
    }

    /* Uploading */

    private static void handleUploadRequest(List<ManifestEntry> serverManifest)
    {
        if (!tryBegin(true))
        {
            message(Formatting.YELLOW, "bbs_synchronized.sync.in_progress");

            return;
        }

        try
        {
            File root = getModelsFolder();
            List<ManifestEntry> local = hashes.buildManifest(root);
            Set<String> remotePaths = new HashSet<>();

            for (ManifestEntry entry : serverManifest)
            {
                remotePaths.add(entry.path);
            }

            List<ManifestEntry> toSend = new ArrayList<>();

            for (ManifestEntry entry : local)
            {
                if (!remotePaths.contains(entry.path))
                {
                    toSend.add(entry);
                }
            }

            if (toSend.isEmpty())
            {
                message(Formatting.GREEN, "bbs_synchronized.upload.nothing");

                return;
            }

            long totalBytes = toSend.stream().mapToLong((e) -> e.size).sum();

            message(Formatting.GRAY, "bbs_synchronized.upload.uploading", toSend.size(), String.format("%.1f", totalBytes / (1024F * 1024F)));

            int tid = 0;
            int sent = 0;

            for (ManifestEntry entry : toSend)
            {
                File file = SyncPaths.resolve(root, entry.path);

                if (file == null || !file.isFile())
                {
                    continue;
                }

                tid += 1;

                PacketByteBuf begin = PacketByteBufs.create();

                begin.writeInt(tid);
                begin.writeString(entry.path);
                begin.writeLong(file.length());
                begin.writeString(entry.sha1);

                ClientPlayNetworking.send(SyncPackets.UP_BEGIN, begin);

                try (InputStream in = new FileInputStream(file))
                {
                    byte[] buffer = new byte[SyncPackets.CHUNK_SIZE];
                    int read;
                    int chunks = 0;

                    while ((read = in.read(buffer)) > 0)
                    {
                        PacketByteBuf chunk = PacketByteBufs.create();

                        chunk.writeInt(tid);

                        /* Same wire format as writeByteArray (varint length +
                         * bytes), without copying the buffer into a new array */
                        chunk.writeVarInt(read);
                        chunk.writeBytes(buffer, 0, read);

                        ClientPlayNetworking.send(SyncPackets.UP_DATA, chunk);

                        /* Pace big uploads so the connection stays responsive */
                        if (++chunks % 32 == 0)
                        {
                            Thread.sleep(20);
                        }
                    }
                }

                PacketByteBuf end = PacketByteBufs.create();

                end.writeInt(tid);
                ClientPlayNetworking.send(SyncPackets.UP_END, end);

                sent += 1;
            }

            ClientPlayNetworking.send(SyncPackets.UP_DONE, PacketByteBufs.empty());

            message(Formatting.GREEN, "bbs_synchronized.upload.done", sent);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (Exception e)
        {
            BBSSynchronized.LOGGER.error("Upload failed", e);
            message(Formatting.RED, "bbs_synchronized.upload.failed", e.getMessage());
        }
        finally
        {
            uploading = false;
        }
    }

    /* Helpers */

    /** Send a prefixed, translated chat message ({@code lang/*.json} keys) */
    private static void message(Formatting color, String key, Object... args)
    {
        MinecraftClient client = MinecraftClient.getInstance();

        client.execute(() ->
        {
            if (client.player != null)
            {
                client.player.sendMessage(Text.translatable("bbs_synchronized.prefix").formatted(Formatting.AQUA)
                    .append(Text.translatable(key, args).formatted(color)));
            }
        });
    }
}
