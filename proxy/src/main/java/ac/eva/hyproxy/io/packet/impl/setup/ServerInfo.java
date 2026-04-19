package ac.eva.hyproxy.io.packet.impl.setup;

import ac.eva.hyproxy.io.proto.HostAddress;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.Pair;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import ac.eva.hyproxy.common.util.ProtocolUtil;
import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.Packet;

@RequiredArgsConstructor
@Getter
@ToString
public class ServerInfo implements Packet {
    private final @Nullable String serverName;
    private final @Nullable String motd;
    private final @Nullable HostAddress fallbackServer;
    private final int maxPlayers;

    public static ServerInfo deserialize(ByteBuf buf) {
        byte nullBits = buf.readByte();
        int maxPlayers = buf.readIntLE();

        int serverNameOffset = buf.readIntLE();
        int motdOffset = buf.readIntLE();
        int fallbackServerOffset = buf.readIntLE();
        int varsOffset = buf.readerIndex();
        int readViaOffsets = 0;

        String serverName = null;
        if ((nullBits & 0x1) != 0) {
            int offset = varsOffset + serverNameOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 100);
            serverName = varString.left();
            readViaOffsets += varString.right();
        }

        String motd = null;
        if ((nullBits & 0x2) != 0) {
            int offset = varsOffset + motdOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 500);
            motd = varString.left();
            readViaOffsets += varString.right();
        }

        HostAddress fallbackServer = null;
        if ((nullBits & 0x4) != 0) {
            int offset = varsOffset + fallbackServerOffset;
            Pair<HostAddress, Integer> hostAddressPair = HostAddress.deserialize(buf, offset);
            fallbackServer = hostAddressPair.left();
            readViaOffsets += hostAddressPair.right();
        }

        buf.skipBytes(readViaOffsets);

        return new ServerInfo(serverName, motd, fallbackServer, maxPlayers);
    }

    @Override
    public void serialize(ByteBuf buf) {
        byte nullBits = 0;

        if (this.serverName != null) {
            nullBits = (byte) (nullBits | 0x1);
        }

        if (this.motd != null) {
            nullBits = (byte) (nullBits | 0x2);
        }

        if (this.fallbackServer != null) {
            nullBits = (byte) (nullBits | 0x4);
        }

        buf.writeByte(nullBits);
        buf.writeIntLE(this.maxPlayers);

        int serverNameOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int motdOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int fallbackServerSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int varsOffset = buf.writerIndex();

        if (this.serverName != null) {
            buf.setIntLE(serverNameOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.serverName);
        }

        if (this.motd != null) {
            buf.setIntLE(motdOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.motd);
        }

        if (this.fallbackServer != null) {
            buf.setIntLE(fallbackServerSlot, buf.writerIndex() - varsOffset);
            this.fallbackServer.serialize(buf);
        }
    }

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }
}
