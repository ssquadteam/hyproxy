package ac.eva.hyproxy.io.packet.impl;

import ac.eva.hyproxy.io.proto.message.FormattedMessage;
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
public class ServerDisconnect implements Packet {
    private final @Nullable FormattedMessage reason;
    private final DisconnectType type;

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }

    public static ServerDisconnect deserialize(ByteBuf buf) {
        byte nullBits = buf.readByte();
        DisconnectType type = DisconnectType.getById(buf.readByte());

        FormattedMessage reason = null;
        if ((nullBits & 0x1) != 0) {
            reason = FormattedMessage.deserialize(buf);
        }

        return new ServerDisconnect(reason, type);
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
            this.reason.serialize(buf);
        }
    }
}
