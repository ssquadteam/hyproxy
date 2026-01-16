package town.kibty.hyproxy.io.proto.param.type;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import town.kibty.hyproxy.io.proto.param.ParamValue;

@RequiredArgsConstructor
@Getter
@ToString
public class BoolParamValue extends ParamValue {
    private final boolean value;

    public static BoolParamValue deserialize(ByteBuf buf) {
        return new BoolParamValue(
                buf.readByte() != 0
        );
    }

    @Override
    public void serialize(ByteBuf buf) {
        buf.writeByte(value ? 1 : 0);
    }
}
