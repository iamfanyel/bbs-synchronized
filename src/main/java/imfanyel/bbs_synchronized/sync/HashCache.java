package imfanyel.bbs_synchronized.sync;

import imfanyel.bbs_synchronized.network.SyncPackets;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SHA-1 hashing of asset files with a (size, mtime) keyed cache, so repeated
 * manifest builds don't rehash unchanged files. Safe to use from worker
 * threads.
 */
public class HashCache
{
    private final Map<String, CachedHash> cache = new ConcurrentHashMap<>();

    /**
     * Build a manifest of every syncable file under the given root folder.
     */
    public List<ManifestEntry> buildManifest(File root)
    {
        List<ManifestEntry> entries = new ArrayList<>();

        this.collect(root, root, entries);
        entries.sort((a, b) -> a.path.compareToIgnoreCase(b.path));

        return entries;
    }

    private void collect(File root, File folder, List<ManifestEntry> entries)
    {
        File[] files = folder.listFiles();

        if (files == null || entries.size() >= SyncPackets.MAX_FILES)
        {
            return;
        }

        for (File file : files)
        {
            if (file.isDirectory())
            {
                this.collect(root, file, entries);
            }
            else if (file.isFile() && file.length() <= SyncPackets.MAX_FILE_SIZE)
            {
                String path = SyncPaths.relativize(root, file);

                if (path != null)
                {
                    String sha1 = this.hash(file);

                    if (sha1 != null)
                    {
                        entries.add(new ManifestEntry(path, file.length(), sha1));
                    }
                }
            }

            if (entries.size() >= SyncPackets.MAX_FILES)
            {
                return;
            }
        }
    }

    /**
     * SHA-1 of a file (lowercase hex), cached by size and modification time.
     * Returns null when the file can't be read.
     */
    public String hash(File file)
    {
        String key = file.getAbsolutePath();
        long size = file.length();
        long mtime = file.lastModified();
        CachedHash cached = this.cache.get(key);

        if (cached != null && cached.size == size && cached.mtime == mtime)
        {
            return cached.sha1;
        }

        String sha1 = sha1(file);

        if (sha1 != null)
        {
            this.cache.put(key, new CachedHash(size, mtime, sha1));
        }

        return sha1;
    }

    public void invalidate(File file)
    {
        this.cache.remove(file.getAbsolutePath());
    }

    /** Drop entries whose files no longer exist, to keep the cache bounded */
    public void prune()
    {
        Iterator<String> it = this.cache.keySet().iterator();

        while (it.hasNext())
        {
            if (!new File(it.next()).exists())
            {
                it.remove();
            }
        }
    }

    public static String sha1(File file)
    {
        try (InputStream in = new FileInputStream(file))
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[65536];
            int read;

            while ((read = in.read(buffer)) > 0)
            {
                digest.update(buffer, 0, read);
            }

            return toHex(digest.digest());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String toHex(byte[] bytes)
    {
        StringBuilder builder = new StringBuilder(bytes.length * 2);

        for (byte b : bytes)
        {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }

        return builder.toString();
    }

    private record CachedHash(long size, long mtime, String sha1) {}
}
