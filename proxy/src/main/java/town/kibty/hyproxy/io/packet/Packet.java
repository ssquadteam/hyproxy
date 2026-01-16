package town.kibty.hyproxy.io.packet;

import io.netty.buffer.ByteBuf;
import town.kibty.hyproxy.io.HytalePacketHandler;

public interface Packet {
    void serialize(ByteBuf buf);
    boolean handle(HytalePacketHandler handler);
}
