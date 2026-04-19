package ac.eva.hyproxy.io.proto.param.type;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import ac.eva.hyproxy.io.proto.param.ParamValue;

@RequiredArgsConstructor
@Getter
@ToString
public class IntParamValue extends ParamValue {
    private final int value;

    public static IntParamValue deserialize(ByteBuf buf) {
        return new IntParamValue(
                buf.readIntLE()
        );
    }

    @Override
    public void serialize(ByteBuf buf) {
        buf.writeIntLE(value);
    }
}
