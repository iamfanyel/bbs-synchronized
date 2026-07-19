package imfanyel.bbs_synchronized;

import imfanyel.bbs_synchronized.network.SyncPayload;
import imfanyel.bbs_synchronized.server.ServerModelSync;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BBS Synchronized — automatic model synchronization for BBS FS servers.
 *
 * <ul>
 *     <li>All server models are distributed automatically upon join.</li>
 *     <li>{@code /bbs model download} re-synchronizes models added after joining.</li>
 *     <li>{@code /bbs model upload} lets players upload their new models.</li>
 * </ul>
 *
 * @author imFanyel
 */
public class BBSSynchronized implements ModInitializer
{
    public static final String MOD_ID = "bbs_synchronized";
    public static final Logger LOGGER = LoggerFactory.getLogger("BBS Synchronized");

    @Override
    public void onInitialize()
    {
        /* The whole sync protocol travels through one multiplexed payload */
        PayloadTypeRegistry.playC2S().register(SyncPayload.ID, SyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncPayload.ID, SyncPayload.CODEC);

        ServerModelSync.init();

        CommandRegistrationCallback.EVENT.register(SyncCommands::register);

        LOGGER.info("BBS Synchronized is ready — models will be kept in sync!");
    }
}
