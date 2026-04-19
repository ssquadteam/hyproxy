package ac.eva.hyproxy.io.packet.impl;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.proto.DisconnectType;
import ac.eva.hyproxy.common.util.ProtocolUtil;

import java.nio.charset.StandardCharsets;

@RequiredArgsConstructor
@Getter
@ToString
public class Disconnect implements Packet {
    private final @Nullable String reason;
    private final DisconnectType type;

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }

    public static Disconnect deserialize(ByteBuf buf) {
        byte nullBits = buf.readByte();
        DisconnectType type = DisconnectType.getById(buf.readByte());

        String reason = null;
        if ((nullBits & 0x1) != 0) {
            reason = ProtocolUtil.readVarString(buf, 1024);
        }

        return new Disconnect(reason, type);
    }

    @Override
    public void serialize(ByteBuf buf) {
        byte nullBits = 0;
        if (this.reason != null) {
            nullBits = (byte) (nullBits | 0x1);
        }

        buf.writeByte(nullBits);
        buf.writeByte(this.type.getId());

        if (this.reason != null) {
            ProtocolUtil.writeVarString(buf, this.reason, StandardCharsets.UTF_8);
        }
    }
}
