package ac.eva.hyproxy.io.packet.impl;

import ac.eva.hyproxy.io.HytalePacketHandler;
import ac.eva.hyproxy.io.packet.Packet;
import ac.eva.hyproxy.io.proto.ClientDisconnectReason;
import ac.eva.hyproxy.io.proto.DisconnectType;
import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

@RequiredArgsConstructor
@Getter
@ToString
public class ClientDisconnect implements Packet {
    private final ClientDisconnectReason reason;
    private final DisconnectType type;

    @Override
    public boolean handle(HytalePacketHandler handler) {
        return handler.handle(this);
    }

    public static ClientDisconnect deserialize(ByteBuf buf) {
        ClientDisconnectReason reason = ClientDisconnectReason.getById(buf.readByte());
        DisconnectType type = DisconnectType.getById(buf.readByte());

        return new ClientDisconnect(reason, type);
    }

    @Override
    public void serialize(ByteBuf buf) {
        buf.writeByte(this.reason.getId());
        buf.writeByte(this.type.getId());
    }
}
