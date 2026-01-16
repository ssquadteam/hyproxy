package town.kibty.hyproxy.io.proto.param;

import io.netty.buffer.ByteBuf;
import town.kibty.hyproxy.io.proto.param.type.*;
import town.kibty.hyproxy.common.util.VarIntUtil;

import javax.annotation.Nonnull;

public abstract class ParamValue {
    public static ParamValue deserialize(ByteBuf buf) {
        int typeId = VarIntUtil.read(buf);

        return switch (typeId) {
            case 0 -> StringParamValue.deserialize(buf);
            case 1 -> BoolParamValue.deserialize(buf);
            case 2 -> DoubleParamValue.deserialize(buf);
            case 3 -> IntParamValue.deserialize(buf);
            case 4 -> LongParamValue.deserialize(buf);
            default -> throw new IllegalStateException("unexpected param value type " + typeId);
        };
    }


    public int getTypeId() {
        return switch (this) {
            case StringParamValue _ -> 0;
            case BoolParamValue _ -> 1;
            case DoubleParamValue _ -> 2;
            case IntParamValue _ -> 3;
            case LongParamValue _ -> 4;
            default -> throw new IllegalStateException("unexpected param value " + this.getClass().getSimpleName());
        };

    }

    public abstract void serialize(ByteBuf buf);

    public void serializeWithTypeId(@Nonnull ByteBuf buf) {
        VarIntUtil.write(buf, this.getTypeId());
        this.serialize(buf);
    }
}
