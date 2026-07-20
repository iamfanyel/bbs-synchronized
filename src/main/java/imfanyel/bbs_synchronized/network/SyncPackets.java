package imfanyel.bbs_synchronized.network;

import imfanyel.bbs_synchronized.sync.ManifestEntry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * Channel constants and shared wire helpers of the BBS Synchronized protocol.
 *
 * <p>All logical channels travel through the single {@link SyncPayload};
 * file payloads are chunked so that every packet stays well below the 32767
 * byte limit of serverbound custom payloads (the tighter of the two
 * directions). Transfers are identified by an integer transfer id so multiple
 * files can be tracked independently and recovered gracefully.</p>
 */
public class SyncPackets
{
    /* Clientbound (S2C) channels */

    /** Server model manifest slice: reason byte, last flag, entries */
    public static final int CH_MANIFEST = 1;

    /** File download stream: begin (tid, path, size, sha1) */
    public static final int CH_FILE_BEGIN = 2;

    /** File download stream: data (tid, bytes) */
    public static final int CH_FILE_DATA = 3;

    /** File download stream: end (tid) */
    public static final int CH_FILE_END = 4;

    /** All requested files were streamed (empty body) */
    public static final int CH_BATCH_DONE = 5;

    /** Server asks the client to start an upload: force flag, last flag, manifest slice */
    public static final int CH_UPLOAD_GO = 6;

    /** Per-file upload result: tid, status byte, path */
    public static final int CH_UPLOAD_ACK = 7;

    /* Serverbound (C2S) channels */

    /** Client announces itself and asks for the join manifest (empty body) */
    public static final int CH_HELLO = 8;

    /** Client asks for a full (authoritative) sync — used by the F6 buttons */
    public static final int CH_REQUEST_SYNC = 14;

    /** Client asks to start a normal upload — used by the F6 buttons */
    public static final int CH_REQUEST_UPLOAD = 15;

    /** Client requests files from the manifest: last flag, path slice */
    public static final int CH_REQUEST_FILES = 9;

    /** File upload stream: begin (tid, path, size, sha1, force) */
    public static final int CH_UP_BEGIN = 10;

    /** File upload stream: data (tid, bytes) */
    public static final int CH_UP_DATA = 11;

    /** File upload stream: end (tid) */
    public static final int CH_UP_END = 12;

    /** Client finished its upload batch (empty body) */
    public static final int CH_UP_DONE = 13;

    /* Manifest reasons */
    public static final byte REASON_JOIN = 0;
    public static final byte REASON_RELOAD = 1;

    /* Upload ack statuses */
    public static final byte ACK_OK = 0;
    public static final byte ACK_SKIPPED = 1;
    public static final byte ACK_FAILED = 2;

    /** Server-internal ACK_OK variant meaning the file was actually written
     *  to disk (mapped to ACK_OK on the wire, counted for the broadcast) */
    public static final byte ACK_WRITTEN = 3;

    /**
     * Payload bytes per chunk. Serverbound custom payloads are limited to
     * 32767 bytes total, so leave headroom for the chunk header.
     */
    public static final int CHUNK_SIZE = 28_000;

    /** Byte budget for serverbound list packets (32767-byte packet limit) */
    public static final int SAFE_C2S_BYTES = 24_000;

    /** Byte budget for clientbound list packets (1 MiB packet limit) */
    public static final int SAFE_S2C_BYTES = 500_000;

    /** Hard cap for a single synchronized file */
    public static final long MAX_FILE_SIZE = 512L * 1024L * 1024L;

    /** Hard cap of files per manifest / request / upload batch */
    public static final int MAX_FILES = 8192;

    /** Build a payload for the given channel; a null writer means an empty body */
    public static SyncPayload make(int channel, Consumer<PacketByteBuf> writer)
    {
        if (writer == null)
        {
            return new SyncPayload(channel, new byte[0]);
        }

        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());

        writer.accept(buf);

        byte[] data = new byte[buf.readableBytes()];

        buf.readBytes(data);
        buf.release();

        return new SyncPayload(channel, data);
    }

    /** View a payload body as a readable packet buffer */
    public static PacketByteBuf wrap(byte[] data)
    {
        return new PacketByteBuf(Unpooled.wrappedBuffer(data));
    }

    public static void writeManifest(PacketByteBuf buf, List<ManifestEntry> entries)
    {
        buf.writeInt(entries.size());

        for (ManifestEntry entry : entries)
        {
            buf.writeString(entry.path);
            buf.writeLong(entry.size);
            buf.writeString(entry.sha1);
        }
    }

    public static List<ManifestEntry> readManifest(PacketByteBuf buf)
    {
        int count = Math.min(buf.readInt(), MAX_FILES);
        List<ManifestEntry> entries = new ArrayList<>(Math.max(count, 0));

        for (int i = 0; i < count; i++)
        {
            entries.add(new ManifestEntry(buf.readString(), buf.readLong(), buf.readString()));
        }

        return entries;
    }

    /* Chunked list transport: lists can exceed a single packet's size limit
     * (32 KiB serverbound, 1 MiB clientbound), so they're split into slices
     * that each fit, and the receiver accumulates until a slice is flagged
     * as the last one. */

    @FunctionalInterface
    public interface ChunkSender<T>
    {
        void send(List<T> slice, boolean last);
    }

    public static void chunkManifest(List<ManifestEntry> entries, int byteBudget, ChunkSender<ManifestEntry> sender)
    {
        chunk(entries, byteBudget, SyncPackets::entryCost, sender);
    }

    public static void chunkPaths(List<String> paths, int byteBudget, ChunkSender<String> sender)
    {
        chunk(paths, byteBudget, (path) -> path.length() * 3 + 5, sender);
    }

    private static <T> void chunk(List<T> items, int byteBudget, ToIntFunction<T> cost, ChunkSender<T> sender)
    {
        List<T> slice = new ArrayList<>();
        int bytes = 0;

        for (T item : items)
        {
            int itemCost = cost.applyAsInt(item);

            if (!slice.isEmpty() && bytes + itemCost > byteBudget)
            {
                sender.send(slice, false);
                slice = new ArrayList<>();
                bytes = 0;
            }

            slice.add(item);
            bytes += itemCost;
        }

        /* Always send the final slice, even when empty — it carries the
         * "last" flag that tells the receiver the list is complete */
        sender.send(slice, true);
    }

    /** Worst-case serialized size of a manifest entry (UTF-8) */
    private static int entryCost(ManifestEntry entry)
    {
        return entry.path.length() * 3 + entry.sha1.length() + 24;
    }
}
