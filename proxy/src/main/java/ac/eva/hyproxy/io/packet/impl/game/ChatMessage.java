package ac.eva.hyproxy.io.packet.impl.game;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.common.util.ProtocolUtil;

import java.nio.charset.StandardCharsets;

@RequiredArgsConstructor
@Getter
@ToString
public class ChatMessage implements Packet {
    private final @Nullable String message;

    public static ChatMessage deserialize(ByteBuf buf) {
        byte nullBits = buf.readByte();

        String message = null;
        if ((nullBits & 0x1) != 0) {
            message = ProtocolUtil.readVarString(buf, 1024, StandardCharsets.UTF_8);
        }

        return new ChatMessage(message);
    }

    @Override
    public void serialize(ByteBuf buf) {
        byte nullBits = 0;
        if (this.message != null) {
            nullBits = (byte) (nullBits | 0x1);
        }

        buf.writeByte(nullBits);

        if (this.message != null) {
            ProtocolUtil.writeVarString(buf, message, StandardCharsets.UTF_8);
        }
    }

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }
}
