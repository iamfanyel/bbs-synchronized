package imfanyel.bbs_synchronized.network;

import imfanyel.bbs_synchronized.BBSSynchronized;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.function.Consumer;

/**
 * Server half of the version-specific transport (1.20.x Identifier
 * networking). All sync traffic is multiplexed through one play channel as a
 * channel byte plus the packet body — the rest of the mod only ever talks
 * channels and buffers, so this file (and its client counterpart) is the only
 * networking code that changes between Minecraft versions.
 */
public class SyncNetwork
{
    /** The single play channel all sync traffic travels through */
    public static final Identifier CHANNEL = new Identifier(BBSSynchronized.MOD_ID, "sync");

    @FunctionalInterface
    public interface Handler
    {
        void receive(MinecraftServer server, ServerPlayerEntity player, int channel, PacketByteBuf buf);
    }

    public static void init(Handler handler)
    {
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL, (server, player, playHandler, buf, responder) ->
        {
            int channel = buf.readByte();
            byte[] body = new byte[buf.readableBytes()];

            buf.readBytes(body);
            handler.receive(server, player, channel, new PacketByteBuf(Unpooled.wrappedBuffer(body)));
        });
    }

    /** Send to a player; a null writer means an empty body */
    public static void send(ServerPlayerEntity player, int channel, Consumer<PacketByteBuf> writer)
    {
        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeByte(channel);

        if (writer != null)
        {
            writer.accept(buf);
        }

        ServerPlayNetworking.send(player, CHANNEL, buf);
    }

    public static boolean canSend(ServerPlayerEntity player)
    {
        return ServerPlayNetworking.canSend(player, CHANNEL);
    }
}
