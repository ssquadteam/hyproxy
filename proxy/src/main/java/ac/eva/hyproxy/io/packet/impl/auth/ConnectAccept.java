package ac.eva.hyproxy.io.packet.impl.auth;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.common.util.VarIntUtil;

@Getter
@RequiredArgsConstructor
@ToString
public class ConnectAccept implements Packet {
    private final byte @Nullable [] passwordChallenge;

    public static ConnectAccept deserialize(ByteBuf buf) {
        byte nullBits = buf.readByte();

        byte[] passwordChallenge = null;

        if ((nullBits & 0x1) != 0) {
            int length = VarIntUtil.read(buf);

            passwordChallenge = new byte[length];
            buf.readBytes(passwordChallenge);
        }
        return new ConnectAccept(passwordChallenge);
    }

    @Override
    public void serialize(ByteBuf buf) {
        byte nullBits = 0;

        if (this.passwordChallenge != null) {
            nullBits = (byte) (nullBits | 0x1);
        }

        buf.writeByte(nullBits);
        if (this.passwordChallenge != null) {
            VarIntUtil.write(buf, this.passwordChallenge.length);
            buf.writeBytes(this.passwordChallenge);
        }
    }

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }
}
