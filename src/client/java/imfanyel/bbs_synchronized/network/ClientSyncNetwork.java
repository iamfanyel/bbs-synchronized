package imfanyel.bbs_synchronized.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;

import java.util.function.Consumer;

/**
 * Client half of the version-specific transport (1.20.5+ typed payload
 * networking). See {@link SyncNetwork} for the payload layout.
 */
public class ClientSyncNetwork
{
    @FunctionalInterface
    public interface Handler
    {
        void receive(int channel, PacketByteBuf buf);
    }

    public static void init(Handler handler)
    {
        ClientPlayNetworking.registerGlobalReceiver(SyncPayload.ID, (payload, context) ->
            handler.receive(payload.channel(), SyncNetwork.wrap(payload.data())));
    }

    /** Send to the server; a null writer means an empty body */
    public static void send(int channel, Consumer<PacketByteBuf> writer)
    {
        ClientPlayNetworking.send(SyncNetwork.make(channel, writer));
    }

    public static boolean canSend()
    {
        return ClientPlayNetworking.canSend(SyncPayload.ID);
    }
}
