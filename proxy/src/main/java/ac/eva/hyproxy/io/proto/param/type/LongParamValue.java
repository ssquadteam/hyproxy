package ac.eva.hyproxy.io.proto.param.type;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import ac.eva.hyproxy.io.proto.param.ParamValue;

@RequiredArgsConstructor
@Getter
@ToString
public class LongParamValue extends ParamValue {
    private final long value;

    public static LongParamValue deserialize(ByteBuf buf) {
        return new LongParamValue(
                buf.readLongLE()
        );
    }

    @Override
    public void serialize(ByteBuf buf) {
        buf.writeLongLE(value);
    }
}
