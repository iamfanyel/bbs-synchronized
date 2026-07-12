package imfanyel.bbs_synchronized.sync;

/**
 * A single file in a model manifest. The path is relative to the
 * {@code config/bbs/assets/models} folder and always uses forward slashes.
 */
public class ManifestEntry
{
    public final String path;
    public final long size;
    public final String sha1;

    public ManifestEntry(String path, long size, String sha1)
    {
        this.path = path;
        this.size = size;
        this.sha1 = sha1;
    }
}
