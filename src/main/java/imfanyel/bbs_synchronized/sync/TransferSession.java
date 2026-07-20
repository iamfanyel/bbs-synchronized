package imfanyel.bbs_synchronized.sync;

import imfanyel.bbs_synchronized.BBSSynchronized;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * A single in-flight file transfer, used by both the server (uploads) and the
 * client (downloads): chunks are appended to a temporary file, and the result
 * is only moved into its final location after the size and SHA-1 hash have
 * been verified.
 */
public class TransferSession
{
    public final String path;
    public final long size;
    public final String sha1;

    /** Last time a chunk arrived, used to sweep stale transfers */
    public volatile long lastActivity;

    /** Server-side: whether this upload may overwrite an existing store file */
    public boolean force;

    private final File tmp;
    private final OutputStream out;
    private long written;

    public TransferSession(String path, long size, String sha1, File tmp) throws IOException
    {
        this.path = path;
        this.size = size;
        this.sha1 = sha1;
        this.tmp = tmp;
        this.out = new FileOutputStream(tmp);
        this.lastActivity = System.currentTimeMillis();
    }

    /**
     * Append a chunk. Returns false when the chunk would overflow the
     * announced size or the disk write fails.
     */
    public boolean write(byte[] data)
    {
        this.lastActivity = System.currentTimeMillis();

        if (this.written + data.length > this.size)
        {
            return false;
        }

        try
        {
            this.out.write(data);
            this.written += data.length;

            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Close the stream and verify the received data against the announced
     * size and hash. The temp file is deleted when it doesn't match.
     */
    public boolean verify()
    {
        try
        {
            this.out.close();

            if (this.written == this.size && this.sha1.equals(HashCache.sha1(this.tmp)))
            {
                return true;
            }
        }
        catch (Exception e)
        {}

        this.tmp.delete();

        return false;
    }

    /** Move the verified temp file into its final location */
    public boolean moveTo(File target)
    {
        try
        {
            target.getParentFile().mkdirs();
            Files.move(this.tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);

            return true;
        }
        catch (Exception e)
        {
            BBSSynchronized.LOGGER.error("Couldn't move transferred file \"{}\" into place", this.path, e);
            this.tmp.delete();

            return false;
        }
    }

    /** Delete the temp file without moving it (e.g. skipped upload) */
    public void discard()
    {
        this.tmp.delete();
    }

    /** Close and delete an unfinished transfer */
    public void abort()
    {
        try
        {
            this.out.close();
        }
        catch (Exception e)
        {}

        this.tmp.delete();
    }
}
