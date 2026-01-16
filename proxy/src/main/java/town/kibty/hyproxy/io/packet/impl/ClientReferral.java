package town.kibty.hyproxy.io.packet.impl;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import town.kibty.hyproxy.io.HytalePacketHandler;
import town.kibty.hyproxy.io.packet.Packet;
import town.kibty.hyproxy.io.proto.HostAddress;
import town.kibty.hyproxy.common.util.VarIntUtil;

@RequiredArgsConstructor
@Getter
public class ClientReferral implements Packet {
    private final @Nullable HostAddress hostTo;
    private final byte @Nullable [] data;

    public static ClientReferral deserialize(ByteBuf buf) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }

    @Override
    public void serialize(ByteBuf buf) {
        byte nullBits = 0;

        if (this.hostTo != null)
            nullBits = (byte) (nullBits | 0x1);

        if (this.data != null)
            nullBits = (byte) (nullBits | 0x2);

        buf.writeByte(nullBits);

        int hostToOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);

        int dataOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);

        int varsOffset = buf.writerIndex();
        if (this.hostTo != null) {
            buf.setIntLE(hostToOffsetSlot, buf.writerIndex() - varsOffset);
            this.hostTo.serialize(buf);
        }

        if (this.data != null) {
            buf.setIntLE(dataOffsetSlot, buf.writerIndex() - varsOffset);

            VarIntUtil.write(buf, this.data.length);
            buf.writeBytes(this.data);
        }
    }
}
