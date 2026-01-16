package town.kibty.hyproxy.common.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

import java.util.Base64;
import java.util.UUID;

@UtilityClass
public class ProxyCommunicationUtil {
    private static final String PROXY_COMMUNICATION_HEADER = "hyProxy.";

    // PLEASE release the buf you pass in after you call this
    public @Nullable ProxyCommunicationMessage deserializeMessage(String input) {
        try {
            if (!input.startsWith(PROXY_COMMUNICATION_HEADER)) {
                return null;
            }
            byte[] arr = Base64.getDecoder().decode(input.substring(PROXY_COMMUNICATION_HEADER.length()));

            ByteBuf buf = Unpooled.copiedBuffer(arr);
            try {
                int typeId = buf.readIntLE();

                return switch (typeId) {
                    case 0 -> new ProxyCommunicationMessage.SendToBackend(
                            ProtocolUtil.readVarString(buf, 128),
                            ProtocolUtil.readUUID(buf),
                            ProtocolUtil.readUUID(buf)
                    );
                    default -> {
                        byte[] dump = new byte[buf.readableBytes()];
                        buf.readBytes(dump);
                        yield new ProxyCommunicationMessage.Unknown(dump);
                    }
                };
            } finally {
                buf.release();
            }
        } catch (Exception e) {
            return null;
        }

    }

    public String serializeMessage(ProxyCommunicationMessage message) {
        ByteBuf buf = Unpooled.buffer(4);

        byte[] dump;

        try {
            buf.writeIntLE(message.getTypeId());

            message.serialize(buf);

            dump = new byte[buf.readableBytes()];
            buf.readBytes(dump);
        } finally {
            buf.release();
        }

        return PROXY_COMMUNICATION_HEADER + Base64.getEncoder().encodeToString(dump);
    }

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
}
