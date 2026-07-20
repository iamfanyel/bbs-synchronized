package imfanyel.bbs_synchronized.client;

import imfanyel.bbs_synchronized.BBSSynchronized;
import imfanyel.bbs_synchronized.network.ClientSyncNetwork;
import imfanyel.bbs_synchronized.network.SyncPackets;
import imfanyel.bbs_synchronized.sync.HashCache;
import imfanyel.bbs_synchronized.sync.ManifestEntry;
import imfanyel.bbs_synchronized.sync.SyncExecutors;
import imfanyel.bbs_synchronized.sync.SyncPaths;
import imfanyel.bbs_synchronized.sync.TransferSession;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSResources;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.utils.colors.Colors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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

    /** Set on join for the integrated-server host: publish new models to the
     *  sync store automatically once the join sync settles */
    private static boolean pendingAutoUpload;

    /** Suppresses the "nothing to upload" message for automatic uploads */
    private static volatile boolean quietUpload;

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

        ClientSyncNetwork.init((channel, buf) ->
        {
            switch (channel)
            {
                case SyncPackets.CH_MANIFEST -> receiveManifestSlice(buf);
                case SyncPackets.CH_FILE_BEGIN ->
                {
                    int tid = buf.readInt();
                    String path = buf.readString();
                    long size = buf.readLong();
                    String sha1 = buf.readString();

                    io.submit(() -> beginDownload(tid, path, size, sha1));
                }
                case SyncPackets.CH_FILE_DATA ->
                {
                    int tid = buf.readInt();
                    byte[] data = buf.readByteArray();

                    io.submit(() -> downloadData(tid, data));
                }
                case SyncPackets.CH_FILE_END ->
                {
                    int tid = buf.readInt();

                    io.submit(() -> endDownload(tid));
                }
                case SyncPackets.CH_BATCH_DONE -> io.submit(ClientModelSync::finishBatch);
                case SyncPackets.CH_UPLOAD_GO -> receiveUploadGoSlice(buf);
                case SyncPackets.CH_UPLOAD_ACK ->
                {
                    buf.readInt();

                    byte status = buf.readByte();
                    String path = buf.readString();

                    if (status == SyncPackets.ACK_SKIPPED)
                    {
                        message(MsgType.WARN, "bbs_synchronized.upload.skipped", path);
                    }
                    else if (status == SyncPackets.ACK_FAILED)
                    {
                        message(MsgType.ERROR, "bbs_synchronized.upload.rejected", path);
                    }
                }
                default -> {}
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
        {
            sentHello = false;
            joinTicks = 0;
            pendingAutoUpload = false;
            quietUpload = false;
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());

        ClientTickEvents.END_CLIENT_TICK.register((client) ->
        {
            /* Delayed hello: wait for the server to announce its channels,
             * retrying every second until it does (or clearly never will).
             * The integrated-server host syncs too — the sync store is
             * separate from client assets, so hosts download like everyone */
            if (client.player != null && !sentHello)
            {
                joinTicks++;

                if (joinTicks > HELLO_GIVE_UP_TICKS)
                {
                    sentHello = true;
                }
                else if (joinTicks >= 40 && joinTicks % 20 == 0 && ClientSyncNetwork.canSend())
                {
                    sentHello = true;

                    BBSSynchronized.LOGGER.info("Requesting model manifest from the server");
                    ClientSyncNetwork.send(SyncPackets.CH_HELLO, null);

                    /* The host seeds the sync store with their own new models
                     * automatically, mirroring the automatic download */
                    pendingAutoUpload = client.isIntegratedServerRunning();
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

                    message(MsgType.ERROR, "bbs_synchronized.sync.timeout");
                    finishBatch();
                });
            }
        });
    }

    /* Transport handlers run on a single thread, so the slice accumulators
     * below are never touched concurrently. */

    private static void receiveManifestSlice(PacketByteBuf buf)
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
    }

    private static void receiveUploadGoSlice(PacketByteBuf buf)
    {
        boolean force = buf.readBoolean();
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
        workers.submit(() -> handleUploadRequest(force, manifest));
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
                message(MsgType.WARN, "bbs_synchronized.sync.in_progress");
            }

            return;
        }

        /* The automatic join sync is additive only — it never rewrites a
         * local file that differs from the server's copy. Only the explicit
         * /bbs model download command does a full, server-authoritative sync */
        boolean full = reason == SyncPackets.REASON_RELOAD;

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

            boolean missing = !local.isFile();

            if (missing || (full && (local.length() != entry.size || !entry.sha1.equals(hashes.hash(local)))))
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
                message(MsgType.SUCCESS, "bbs_synchronized.sync.up_to_date");
            }

            maybeAutoUpload();

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

        message(MsgType.INFO, "bbs_synchronized.sync.downloading", countModels(needed), String.format("%.1f", totalBytes / (1024F * 1024F)));

        /* The request list can exceed the 32 KiB serverbound packet limit,
         * so it's sent as chunked slices */
        SyncPackets.chunkPaths(needed, SyncPackets.SAFE_C2S_BYTES, (slice, last) ->
            ClientSyncNetwork.send(SyncPackets.CH_REQUEST_FILES, (buf) ->
            {
                buf.writeBoolean(last);
                buf.writeInt(slice.size());

                for (String path : slice)
                {
                    buf.writeString(path);
                }
            }));
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
                message(MsgType.INFO, "bbs_synchronized.sync.progress", count, expectedCount);
            }
        }
        else
        {
            message(MsgType.ERROR, "bbs_synchronized.sync.verify_failed", session.path);
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
            maybeAutoUpload();

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

        message(MsgType.SUCCESS, "bbs_synchronized.sync.done", countModels(updated));
        maybeAutoUpload();
    }

    /* Uploading */

    /** After the join sync settles, the integrated-server host publishes any
     *  models the store doesn't have yet — quietly when there's nothing new */
    private static void maybeAutoUpload()
    {
        if (!pendingAutoUpload)
        {
            return;
        }

        pendingAutoUpload = false;

        if (MinecraftClient.getInstance().isIntegratedServerRunning())
        {
            quietUpload = true;
            requestUploadNew();
        }
    }

    private static void handleUploadRequest(boolean force, List<ManifestEntry> serverManifest)
    {
        boolean quiet = quietUpload;

        quietUpload = false;

        if (!tryBegin(true))
        {
            message(MsgType.WARN, "bbs_synchronized.sync.in_progress");

            return;
        }

        try
        {
            File root = getModelsFolder();
            List<ManifestEntry> local = hashes.buildManifest(root);
            Map<String, String> remoteHashes = new HashMap<>();

            for (ManifestEntry entry : serverManifest)
            {
                remoteHashes.put(entry.path, entry.sha1);
            }

            List<ManifestEntry> toSend = new ArrayList<>();

            for (ManifestEntry entry : local)
            {
                String remoteSha1 = remoteHashes.get(entry.path);

                /* Normal uploads send only files the server lacks; forced
                 * uploads also resend files whose contents differ */
                if (remoteSha1 == null || (force && !remoteSha1.equals(entry.sha1)))
                {
                    toSend.add(entry);
                }
            }

            if (toSend.isEmpty())
            {
                if (!quiet)
                {
                    message(MsgType.SUCCESS, force
                        ? "bbs_synchronized.upload.nothing_forced"
                        : "bbs_synchronized.upload.nothing");
                }

                return;
            }

            long totalBytes = toSend.stream().mapToLong((e) -> e.size).sum();

            message(MsgType.INFO, "bbs_synchronized.upload.uploading", countModels(toSend.stream().map((e) -> e.path).toList()), String.format("%.1f", totalBytes / (1024F * 1024F)));

            int tid = 0;
            List<String> sentPaths = new ArrayList<>();

            for (ManifestEntry entry : toSend)
            {
                File file = SyncPaths.resolve(root, entry.path);

                if (file == null || !file.isFile())
                {
                    continue;
                }

                tid += 1;

                final int fileTid = tid;

                ClientSyncNetwork.send(SyncPackets.CH_UP_BEGIN, (begin) ->
                {
                    begin.writeInt(fileTid);
                    begin.writeString(entry.path);
                    begin.writeLong(file.length());
                    begin.writeString(entry.sha1);
                    begin.writeBoolean(force);
                });

                try (InputStream in = new FileInputStream(file))
                {
                    byte[] buffer = new byte[SyncPackets.CHUNK_SIZE];
                    int read;
                    int chunks = 0;

                    while ((read = in.read(buffer)) > 0)
                    {
                        final int chunkLength = read;

                        ClientSyncNetwork.send(SyncPackets.CH_UP_DATA, (chunk) ->
                        {
                            chunk.writeInt(fileTid);
                            chunk.writeVarInt(chunkLength);
                            chunk.writeBytes(buffer, 0, chunkLength);
                        });

                        /* Pace big uploads so the connection stays responsive */
                        if (++chunks % 32 == 0)
                        {
                            Thread.sleep(20);
                        }
                    }
                }

                ClientSyncNetwork.send(SyncPackets.CH_UP_END, (end) -> end.writeInt(fileTid));

                sentPaths.add(entry.path);
            }

            ClientSyncNetwork.send(SyncPackets.CH_UP_DONE, null);

            message(MsgType.SUCCESS, "bbs_synchronized.upload.done", countModels(sentPaths));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (Exception e)
        {
            BBSSynchronized.LOGGER.error("Upload failed", e);
            message(MsgType.ERROR, "bbs_synchronized.upload.failed", e.getMessage());
        }
        finally
        {
            uploading = false;
        }
    }

    /* Helpers */

    /** Feedback kinds, colored with BBS's own palette */
    private enum MsgType
    {
        INFO(0xdddddd), SUCCESS(Colors.GREEN), WARN(Colors.ORANGE), ERROR(Colors.RED);

        public final int color;

        MsgType(int color)
        {
            this.color = color;
        }
    }

    /**
     * Send feedback: a BBS dashboard notification (short text) when the
     * dashboard is open, a BBS-styled chat message otherwise.
     */
    private static void message(MsgType type, String key, Object... args)
    {
        MinecraftClient client = MinecraftClient.getInstance();

        client.execute(() ->
        {
            if (postNotification(type, key, args))
            {
                return;
            }

            if (client.player != null)
            {
                client.player.sendMessage(Text.translatable("bbs_synchronized.prefix").styled((style) -> style.withColor(Colors.BLUE))
                    .append(Text.translatable(key, args).styled((style) -> style.withColor(type.color))));
            }
        });
    }

    /** Post to the BBS dashboard's notification area when it's on screen */
    private static boolean postNotification(MsgType type, String key, Object[] args)
    {
        UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

        if (dashboard == null
            || !(MinecraftClient.getInstance().currentScreen instanceof UIScreen screen)
            || screen.getMenu() != dashboard)
        {
            return false;
        }

        /* Notifications have little space — prefer the short string variant */
        String shortKey = key + ".short";
        IKey text = IKey.raw(I18n.translate(I18n.hasTranslation(shortKey) ? shortKey : key, args));

        switch (type)
        {
            case SUCCESS -> dashboard.context.notifySuccess(text);
            case ERROR -> dashboard.context.notifyError(text);
            case WARN -> dashboard.context.notify(text, Colors.ORANGE);
            default -> dashboard.context.notifyInfo(text);
        }

        return true;
    }

    /** Distinct model folders among the given file paths */
    private static int countModels(Collection<String> paths)
    {
        Set<String> models = new HashSet<>();

        for (String path : paths)
        {
            int slash = path.indexOf('/');

            models.add(slash > 0 ? path.substring(0, slash) : path);
        }

        return models.size();
    }

    /* Triggered by the F6 dashboard buttons — these bypass the chat command
     * so that every bit of feedback can flow through the notifications */

    public static void requestFullSync()
    {
        ClientSyncNetwork.send(SyncPackets.CH_REQUEST_SYNC, null);
    }

    public static void requestUploadNew()
    {
        ClientSyncNetwork.send(SyncPackets.CH_REQUEST_UPLOAD, null);
    }
}
