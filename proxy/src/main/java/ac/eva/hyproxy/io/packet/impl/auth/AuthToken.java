package ac.eva.hyproxy.io.packet.impl.auth;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.Pair;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.common.util.ProtocolUtil;

@RequiredArgsConstructor
@Getter
@ToString
public class AuthToken implements Packet {
    private final @Nullable String accessToken;
    private final @Nullable String serverAuthorizationGrant;

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }

    public static AuthToken deserialize(ByteBuf buf) {
        byte nullBits = buf.readByte();

        int accessTokenOffset = buf.readIntLE();
        int serverAuthorizationGrantOffset = buf.readIntLE();
        int varsOffset = buf.readerIndex();
        int readViaOffsets = 0;

        String accessToken = null;

        if ((nullBits & 0x1) != 0) {
            int offset = varsOffset + accessTokenOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 8192);
            accessToken = varString.left();
            readViaOffsets += varString.right();
        }

        String serverAuthorizationGrant = null;

        if ((nullBits & 0x2) != 0) {
            int offset = varsOffset + serverAuthorizationGrantOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 4096);
            serverAuthorizationGrant = varString.left();
            readViaOffsets += varString.right();
        }

        buf.skipBytes(readViaOffsets);

        return new AuthToken(accessToken, serverAuthorizationGrant);
    }

    @Override
    public void serialize(ByteBuf buf) {
        byte nullBits = 0;
        if (this.accessToken != null) {
            nullBits = (byte) (nullBits | 0x1);
        }

        if (this.serverAuthorizationGrant != null) {
            nullBits = (byte) (nullBits | 0x2);
        }

        buf.writeByte(nullBits);

        int authorizationGrantOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int serverIdentityTokenOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int varsOffset = buf.writerIndex();

        if (this.accessToken != null) {
            buf.setIntLE(authorizationGrantOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.accessToken);
        }

        if (this.serverAuthorizationGrant != null) {
            buf.setIntLE(serverIdentityTokenOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.serverAuthorizationGrant);
        }
    }
}
