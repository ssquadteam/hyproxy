package ac.eva.hyproxy.common.communication;

import io.netty.buffer.ByteBuf;
import ac.eva.hyproxy.common.util.ProtocolUtil;
import ac.eva.hyproxy.common.util.VarIntUtil;

import java.util.UUID;

/**
 * a message sent from the backend to the proxy to do a custom action
 */
public sealed interface ProxyCommunicationMessage permits
        ProxyCommunicationMessage.SendToBackend,
        ProxyCommunicationMessage.Unknown
{
    int getTypeId();
    void serialize(ByteBuf buf);

    record SendToBackend(String backendId, UUID targetId, UUID senderId) implements ProxyCommunicationMessage {
        @Override
        public void serialize(ByteBuf buf) {
            ProtocolUtil.writeVarString(buf, backendId);
            ProtocolUtil.writeUUID(buf, targetId);
            ProtocolUtil.writeUUID(buf, senderId);
        }

        @Override
        public int getTypeId() {
            return 0;
        }
    }

    /**
     * a non-native proxy communication message, this might be custom from a plugin or similar
     * @param data the data that was sent
     */
    record Unknown(byte[] data) implements ProxyCommunicationMessage {
        @Override
        public void serialize(ByteBuf buf) {
            VarIntUtil.write(buf, data.length);
            buf.writeBytes(data);
        }

        @Override
        public int getTypeId() {
            return -1;
        }
    }
}