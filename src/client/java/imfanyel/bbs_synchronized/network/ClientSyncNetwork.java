package imfanyel.bbs_synchronized.network;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;

import java.util.function.Consumer;

/**
 * Client half of the version-specific transport (1.20.x Identifier
 * networking). See {@link SyncNetwork} for the wire layout.
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
        ClientPlayNetworking.registerGlobalReceiver(SyncNetwork.CHANNEL, (client, playHandler, buf, responder) ->
        {
            int channel = buf.readByte();
            byte[] body = new byte[buf.readableBytes()];

            buf.readBytes(body);
            handler.receive(channel, new PacketByteBuf(Unpooled.wrappedBuffer(body)));
        });
    }

    /** Send to the server; a null writer means an empty body */
    public static void send(int channel, Consumer<PacketByteBuf> writer)
    {
        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeByte(channel);

        if (writer != null)
        {
            writer.accept(buf);
        }

        ClientPlayNetworking.send(SyncNetwork.CHANNEL, buf);
    }

    public static boolean canSend()
    {
        return ClientPlayNetworking.canSend(SyncNetwork.CHANNEL);
    }
}
