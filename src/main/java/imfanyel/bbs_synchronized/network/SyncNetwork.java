package imfanyel.bbs_synchronized.network;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.function.Consumer;

/**
 * Server half of the version-specific transport (1.20.5+ typed payload
 * networking). All sync traffic is multiplexed through {@link SyncPayload} —
 * the rest of the mod only ever talks channels and buffers, so this file
 * (and its client counterpart) is the only networking code that changes
 * between Minecraft versions.
 */
public class SyncNetwork
{
    @FunctionalInterface
    public interface Handler
    {
        void receive(MinecraftServer server, ServerPlayerEntity player, int channel, PacketByteBuf buf);
    }

    public static void init(Handler handler)
    {
        PayloadTypeRegistry.playC2S().register(SyncPayload.ID, SyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncPayload.ID, SyncPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(SyncPayload.ID, (payload, context) ->
            handler.receive(context.server(), context.player(), payload.channel(), wrap(payload.data())));
    }

    /** Send to a player; a null writer means an empty body */
    public static void send(ServerPlayerEntity player, int channel, Consumer<PacketByteBuf> writer)
    {
        ServerPlayNetworking.send(player, make(channel, writer));
    }

    public static boolean canSend(ServerPlayerEntity player)
    {
        return ServerPlayNetworking.canSend(player, SyncPayload.ID);
    }

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
}
