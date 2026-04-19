package ac.eva.hyproxy.io.proto.param.type;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import ac.eva.hyproxy.io.proto.param.ParamValue;

@RequiredArgsConstructor
@Getter
@ToString
public class DoubleParamValue extends ParamValue {
    private final double value;

    public static DoubleParamValue deserialize(ByteBuf buf) {
        return new DoubleParamValue(
                buf.readDoubleLE()
        );
    }

    @Override
    public void serialize(ByteBuf buf) {
        buf.writeDoubleLE(value);
    }
}
