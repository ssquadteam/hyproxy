package town.kibty.hyproxy.io.proto.param.type;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import town.kibty.hyproxy.io.proto.param.ParamValue;
import town.kibty.hyproxy.common.util.ProtocolUtil;

import java.nio.charset.StandardCharsets;

@RequiredArgsConstructor
@Getter
@ToString
public class StringParamValue extends ParamValue {
    private final @Nullable String value;

    public static StringParamValue deserialize(ByteBuf buf) {
        byte nullBits = buf.readByte();

        String value = null;
        if ((nullBits & 0x1) != 0) {
            value = ProtocolUtil.readVarString(buf, 1024, StandardCharsets.UTF_8);
        }

        return new StringParamValue(value);
    }

    @Override
    public void serialize(ByteBuf buf) {
        byte nullBits = 0;

        if (this.value != null) {
            nullBits = (byte) (nullBits | 0x1);
        }

        buf.writeByte(nullBits);
        if (this.value != null) {
            ProtocolUtil.writeVarString(buf, this.value, StandardCharsets.UTF_8);
        }
    }
}
