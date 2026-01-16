package town.kibty.hyproxy.io.packet.impl.auth;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.Pair;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import town.kibty.hyproxy.io.HytalePacketHandler;
import town.kibty.hyproxy.io.packet.Packet;
import town.kibty.hyproxy.common.util.ProtocolUtil;
import town.kibty.hyproxy.common.util.VarIntUtil;

@RequiredArgsConstructor
@Getter
@ToString
public class ServerAuthToken implements Packet {
    private final @Nullable String serverAccessToken;
    private final byte @Nullable [] passwordChallenge;

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }

    public static ServerAuthToken deserialize(ByteBuf buf) {
        byte nullBits = buf.readByte();

        int serverAccessTokenOffset = buf.readIntLE();
        int passwordChallengeOffset = buf.readIntLE();
        int varsOffset = buf.readerIndex();
        int readViaOffsets = 0;

        String serverAccessToken = null;

        if ((nullBits & 0x1) != 0) {
            int offset = varsOffset + serverAccessTokenOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 8192);
            serverAccessToken = varString.left();
            readViaOffsets += varString.right();
        }

        byte[] passwordChallenge = null;

        if ((nullBits & 0x2) != 0) {
            int offset = varsOffset + passwordChallengeOffset;

            int varIntLength = VarIntUtil.length(buf, offset);
            int length = VarIntUtil.peek(buf, offset);

            if (length < 0 || length > 64) {
                throw new IllegalStateException("password challenge is larger then 64 bytes or below zero");
            }

            passwordChallenge = new byte[length];
            buf.getBytes(offset + varIntLength, passwordChallenge);

            readViaOffsets += varIntLength + length;
        }

        buf.skipBytes(readViaOffsets);

        return new ServerAuthToken(serverAccessToken, passwordChallenge);
    }

    @Override
    public void serialize(ByteBuf buf) {
        byte nullBits = 0;
        if (this.serverAccessToken != null) {
            nullBits = (byte) (nullBits | 0x1);
        }

        if (this.passwordChallenge != null) {
            nullBits = (byte) (nullBits | 0x2);
        }

        buf.writeByte(nullBits);

        int serverAccessTokenOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int passwordChallengeOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int varsOffset = buf.writerIndex();

        if (this.serverAccessToken != null) {
            buf.setIntLE(serverAccessTokenOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.serverAccessToken);
        }

        if (this.passwordChallenge != null) {
            buf.setIntLE(passwordChallengeOffsetSlot, buf.writerIndex() - varsOffset);
            buf.writeBytes(this.passwordChallenge);
        }
    }
}
