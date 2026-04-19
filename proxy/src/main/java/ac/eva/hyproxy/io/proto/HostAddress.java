package ac.eva.hyproxy.io.proto;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.Pair;
import ac.eva.hyproxy.common.util.ProtocolUtil;

public record HostAddress(
        String host,
        short port
) {

    public void serialize(ByteBuf buf) {
        buf.writeShortLE(this.port);
        ProtocolUtil.writeVarString(buf, this.host);
    }

    public static Pair<HostAddress, Integer> deserialize(ByteBuf buf, int offset) {
        short port = buf.getShortLE(offset);

        Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset + 2, 256);

        return Pair.of(new HostAddress(varString.left(), port), 2 + varString.right());
    }
    public static HostAddress deserialize(ByteBuf buf) {
        short port = buf.readShortLE();
        String host = ProtocolUtil.readVarString(buf, 256);

        return new HostAddress(host, port);
    }
}
