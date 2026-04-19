package ac.eva.hyproxy.io.packet.impl.game;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.proto.message.FormattedMessage;

@RequiredArgsConstructor
@Getter
@ToString
public class ServerMessage implements Packet {
    private final byte type; // only has 0 (CHAT) for now so we dont bother
    private final @Nullable FormattedMessage message;

    public static ServerMessage deserialize(ByteBuf buf) {
        byte nullBits = buf.readByte();
        byte type = buf.readByte();

        FormattedMessage message = null;
        if ((nullBits & 0x1) != 0) {
            message = FormattedMessage.deserialize(buf);
        }

        return new ServerMessage(type, message);
    }

    @Override
    public void serialize(ByteBuf buf) {
        byte nullBits = 0;

        if (this.message != null) {
            nullBits = (byte) (nullBits | 0x1);
        }

        buf.writeByte(nullBits);
        buf.writeByte(this.type);

        if (this.message != null) {
            message.serialize(buf);
        }
    }

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }
}
