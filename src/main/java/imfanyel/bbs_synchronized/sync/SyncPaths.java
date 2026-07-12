package imfanyel.bbs_synchronized.sync;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Validation of file paths that travel over the network. Every path is
 * relative to the {@code models} assets folder, uses forward slashes, and is
 * checked on both sides before it ever touches the file system.
 */
public class SyncPaths
{
    /** File extensions that are allowed to be synchronized */
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
        "json", "png", "jpg", "jpeg", "tga", "obj", "mtl", "bobj", "vox", "txt", "md"
    ));

    /** Windows reserved device names which must never be used as file names */
    private static final Set<String> RESERVED_NAMES = new HashSet<>(Arrays.asList(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    ));

    public static boolean isValid(String path)
    {
        if (path == null || path.isEmpty() || path.length() > 255)
        {
            return false;
        }

        if (path.startsWith("/") || path.endsWith("/") || path.contains("//") || path.contains("\\"))
        {
            return false;
        }

        for (int i = 0; i < path.length(); i++)
        {
            char c = path.charAt(i);

            /* Block control characters and everything Windows forbids in file
             * names; anything else (including non-Latin letters) is allowed */
            if (c < 0x20 || c == 127
                || c == '\\' || c == ':' || c == '*' || c == '?'
                || c == '"' || c == '<' || c == '>' || c == '|')
            {
                return false;
            }
        }

        String extension = "";
        int lastDot = path.lastIndexOf('.');

        if (lastDot >= 0)
        {
            extension = path.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        }

        if (!ALLOWED_EXTENSIONS.contains(extension))
        {
            return false;
        }

        for (String segment : path.split("/"))
        {
            String trimmed = segment.trim();

            if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..") || segment.endsWith(".") || segment.endsWith(" "))
            {
                return false;
            }

            String name = trimmed.toLowerCase(Locale.ROOT);
            int dot = name.indexOf('.');

            if (RESERVED_NAMES.contains(dot >= 0 ? name.substring(0, dot) : name))
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Resolve a validated relative path against a root folder, and double
     * check that the result didn't escape the root.
     */
    public static File resolve(File root, String path)
    {
        if (!isValid(path))
        {
            return null;
        }

        File file = new File(root, path.replace('/', File.separatorChar));

        try
        {
            String canonicalRoot = root.getCanonicalPath() + File.separator;

            if (!file.getCanonicalPath().startsWith(canonicalRoot))
            {
                return null;
            }
        }
        catch (Exception e)
        {
            return null;
        }

        return file;
    }

    /**
     * Turn an absolute file inside the root folder into a relative,
     * forward-slashed manifest path. Returns null when the file is outside
     * the root or the resulting path wouldn't survive validation.
     */
    public static String relativize(File root, File file)
    {
        String rootPath;
        String filePath;

        try
        {
            rootPath = root.getCanonicalPath();
            filePath = file.getCanonicalPath();
        }
        catch (Exception e)
        {
            return null;
        }

        if (!filePath.startsWith(rootPath + File.separator))
        {
            return null;
        }

        String relative = filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');

        return isValid(relative) ? relative : null;
    }
}
