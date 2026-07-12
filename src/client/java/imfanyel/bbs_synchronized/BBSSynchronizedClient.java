package imfanyel.bbs_synchronized;

import imfanyel.bbs_synchronized.client.ClientModelSync;
import net.fabricmc.api.ClientModInitializer;

/**
 * Client entrypoint of BBS Synchronized.
 *
 * @author imFanyel
 */
public class BBSSynchronizedClient implements ClientModInitializer
{
    @Override
    public void onInitializeClient()
    {
        ClientModelSync.init();
    }
}
