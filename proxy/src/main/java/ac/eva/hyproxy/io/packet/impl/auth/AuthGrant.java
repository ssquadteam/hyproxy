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
public class AuthGrant implements Packet {
    private final @Nullable String authorizationGrant;
    private final @Nullable String serverIdentityToken;

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }

    public static AuthGrant deserialize(ByteBuf buf) {
        byte nullBits = buf.readByte();

        int authorizationGrantOffset = buf.readIntLE();
        int serverIdentityTokenOffset = buf.readIntLE();
        int varsOffset = buf.readerIndex();
        int readViaOffsets = 0;

        String authorizationGrant = null;

        if ((nullBits & 0x1) != 0) {
            int offset = varsOffset + authorizationGrantOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 128);
            authorizationGrant = varString.left();
            readViaOffsets += varString.right();
        }

        String serverIdentityToken = null;

        if ((nullBits & 0x2) != 0) {
            int offset = varsOffset + serverIdentityTokenOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 128);
            serverIdentityToken = varString.left();
            readViaOffsets += varString.right();
        }

        buf.skipBytes(readViaOffsets);

        return new AuthGrant(authorizationGrant, serverIdentityToken);
    }

    @Override
    public void serialize(ByteBuf buf) {
        byte nullBits = 0;
        if (this.authorizationGrant != null) {
            nullBits = (byte) (nullBits | 0x1);
        }

        if (this.serverIdentityToken != null) {
            nullBits = (byte) (nullBits | 0x2);
        }

        buf.writeByte(nullBits);

        int authorizationGrantOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int serverIdentityTokenOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int varsOffset = buf.writerIndex();

        if (this.authorizationGrant != null) {
            buf.setIntLE(authorizationGrantOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.authorizationGrant);
        }

        if (this.serverIdentityToken != null) {
            buf.setIntLE(serverIdentityTokenOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.serverIdentityToken);
        }
    }
}
