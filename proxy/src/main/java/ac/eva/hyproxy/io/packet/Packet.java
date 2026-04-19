package ac.eva.hyproxy.io.packet;

import io.netty.buffer.ByteBuf;
import ac.eva.hyproxy.io.HytalePacketHandler;

public interface Packet {
    void serialize(ByteBuf buf);
    boolean handle(HytalePacketHandler handler);
}
