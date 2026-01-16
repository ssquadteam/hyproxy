package town.kibty.hyproxy.io;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import town.kibty.hyproxy.io.packet.Packet;
import town.kibty.hyproxy.io.packet.PacketRegistry;

public class PacketEncoder extends MessageToByteEncoder<Packet> {
    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, Packet packet, ByteBuf buf) throws Exception {
        PacketRegistry.PacketInfo info = PacketRegistry.getPacketByClass(packet.getClass());
        if (info == null) {
            throw new IllegalArgumentException("cannot encode packet that isn't in PacketRegistry");
        }

        int lengthOffset = buf.writerIndex();
        buf.writeIntLE(-1);

        buf.writeIntLE(info.id());
        packet.serialize(buf);

        buf.setIntLE(lengthOffset, buf.readableBytes() - 8);
    }
}
