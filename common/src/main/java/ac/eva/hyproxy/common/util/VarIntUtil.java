package ac.eva.hyproxy.common.util;

import io.netty.buffer.ByteBuf;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class VarIntUtil {
    public int write(ByteBuf buf, int value) {
        int written = 0;
        if (value < 0)
            throw new IllegalArgumentException("varint cannot encode negative values: " + value);
        while ((value & 0xFFFFFF80) != 0) {
            buf.writeByte(value & 0x7F | 0x80);
            written++;
            value >>>= 7;
        }
        buf.writeByte(value);
        written++;
        return written;
    }

    public int read(ByteBuf buf) {
        int value = 0, shift = 0;
        while (true) {
            byte b = buf.readByte();
            value |= (b & Byte.MAX_VALUE) << shift;
            if ((b & 0x80) == 0)
                return value;
            shift += 7;
            if (shift > 28)
                throw new IllegalStateException("varint exceeds maximum length (5 bytes)");
        }
    }

    public int peek(ByteBuf buf, int index) {
        int value = 0, shift = 0;
        int pos = index;
        while (pos < buf.writerIndex()) {
            byte b = buf.getByte(pos++);
            value |= (b & Byte.MAX_VALUE) << shift;
            if ((b & 0x80) == 0)
                return value;
            shift += 7;
            if (shift > 28)
                return -1;
        }
        return -1;
    }

    public int length(ByteBuf buf, int index) {
        int pos = index;
        while (pos < buf.writerIndex()) {
            if ((buf.getByte(pos++) & 0x80) == 0)
                return pos - index;
            if (pos - index > 5)
                return -1;
        }
        return -1;
    }

    public int size(int value) {
        if ((value & 0xFFFFFF80) == 0)
            return 1;
        if ((value & 0xFFFFC000) == 0)
            return 2;
        if ((value & 0xFFE00000) == 0)
            return 3;
        if ((value & 0xF0000000) == 0)
            return 4;
        return 5;
    }
}