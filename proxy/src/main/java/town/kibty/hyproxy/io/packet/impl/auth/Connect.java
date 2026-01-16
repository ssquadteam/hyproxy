package town.kibty.hyproxy.io.packet.impl.auth;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.Pair;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import town.kibty.hyproxy.io.HytalePacketHandler;
import town.kibty.hyproxy.io.packet.Packet;
import town.kibty.hyproxy.io.proto.ClientType;
import town.kibty.hyproxy.io.proto.HostAddress;
import town.kibty.hyproxy.common.util.ProtocolUtil;
import town.kibty.hyproxy.common.util.VarIntUtil;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Getter
@RequiredArgsConstructor
@ToString
public class Connect implements Packet {
    private final String protocolHash;
    private final ClientType clientType;
    private final UUID uuid;
    private final @Nullable String language;
    private final @Nullable String identityToken;
    private final String username;
    private final byte @Nullable [] referralData;
    private final @Nullable HostAddress referralSource;

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }

    public static Connect deserialize(ByteBuf buf) {
        byte nullBits = buf.readByte();

        byte[] protocolHashBytes = new byte[64];
        buf.readBytes(protocolHashBytes);
        String protocolHash = new String(protocolHashBytes);

        ClientType clientType = ClientType.getById(buf.readByte());
        UUID uuid = ProtocolUtil.readUUID(buf);

        int languageOffset = buf.readIntLE();
        int identityTokenOffset = buf.readIntLE();
        int usernameOffset = buf.readIntLE();
        int referralDataOffset = buf.readIntLE();
        int referralSourceOffset = buf.readIntLE();
        int varsOffset = buf.readerIndex();

        int readViaOffsets = 0;
        String language = null;
        if ((nullBits & 0x1) != 0) {
            int offset = varsOffset + languageOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 128);
            language = varString.left();
            readViaOffsets += varString.right();
        }

        String identityToken = null;

        if ((nullBits & 0x2) != 0) {
            int offset = varsOffset + identityTokenOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 8192);
            identityToken = varString.left();
            readViaOffsets += varString.right();
        }

        int absoluteUsernameOffset = varsOffset + usernameOffset;
        Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, absoluteUsernameOffset, 16);
        String username = varString.left();
        readViaOffsets += varString.right();

        byte[] referralData = null;

        if ((nullBits & 0x4) != 0) {
            int offset = varsOffset + referralDataOffset;

            int varIntLength = VarIntUtil.length(buf, offset);
            int length = VarIntUtil.peek(buf, offset);

            if (length < 0 || length > 4096) {
                throw new IllegalStateException("referral data is larger then 4096 bytes or below zero");
            }

            referralData = new byte[length];
            buf.getBytes(offset + varIntLength, referralData);

            readViaOffsets += varIntLength + length;
        }

        HostAddress referralSource = null;

        if ((nullBits & 0x8) != 0) {
            int offset = varsOffset + referralSourceOffset;

            Pair<HostAddress, Integer> hostAddressPair = HostAddress.deserialize(buf, offset);
            referralSource = hostAddressPair.left();
            readViaOffsets += hostAddressPair.right();
        }


        buf.readerIndex(varsOffset + readViaOffsets);
        return new Connect(protocolHash, clientType, uuid, language, identityToken, username, referralData, referralSource);
    }

    @Override
    public void serialize(ByteBuf buf) {
        byte nullBits = 0;
        if (this.language != null)
            nullBits = (byte) (nullBits | 0x1);

        if (this.identityToken != null)
            nullBits = (byte) (nullBits | 0x2);

        if (this.referralData != null)
            nullBits = (byte) (nullBits | 0x4);

        if (this.referralSource != null)
            nullBits = (byte) (nullBits | 0x8);

        buf.writeByte(nullBits);
        buf.writeBytes(this.protocolHash.getBytes(StandardCharsets.UTF_8));
        buf.writeByte(this.clientType.getId());
        ProtocolUtil.writeUUID(buf, this.uuid);

        int languageOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int identityTokenOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int usernameOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int referralDataOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int referralSourceOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);

        int varsOffset = buf.writerIndex();
        if (this.language != null) {
            buf.setIntLE(languageOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.language);
        }

        if (this.identityToken != null) {
            buf.setIntLE(identityTokenOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.identityToken);
        }

        buf.setIntLE(usernameOffsetSlot, buf.writerIndex() - varsOffset);
        ProtocolUtil.writeVarString(buf, this.username);

        if (this.referralData != null) {
            buf.setIntLE(referralDataOffsetSlot, buf.writerIndex() - varsOffset);
            VarIntUtil.write(buf, this.referralData.length);
            buf.writeBytes(this.referralData);
        }

        if (this.referralSource != null) {
            buf.setIntLE(referralSourceOffsetSlot, buf.writerIndex() - varsOffset);
            this.referralSource.serialize(buf);
        }
    }
}
