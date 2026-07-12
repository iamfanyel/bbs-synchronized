package imfanyel.bbs_synchronized.network;

import imfanyel.bbs_synchronized.BBSSynchronized;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * The single custom payload of the sync protocol (1.20.5+ typed networking).
 * All logical channels are multiplexed through it as a channel byte plus the
 * raw packet body, which keeps the whole pipeline identical to the 1.20.4
 * version — only the transport wrapper differs.
 */
public record SyncPayload(int channel, byte[] data) implements CustomPayload
{
    public static final CustomPayload.Id<SyncPayload> ID = new CustomPayload.Id<>(Identifier.of(BBSSynchronized.MOD_ID, "sync"));

    public static final PacketCodec<PacketByteBuf, SyncPayload> CODEC = new PacketCodec<>()
    {
        @Override
        public SyncPayload decode(PacketByteBuf buf)
        {
            return new SyncPayload(buf.readByte(), buf.readByteArray());
        }

        @Override
        public void encode(PacketByteBuf buf, SyncPayload payload)
        {
            buf.writeByte(payload.channel);
            buf.writeByteArray(payload.data);
        }
    };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId()
    {
        return ID;
    }
}
