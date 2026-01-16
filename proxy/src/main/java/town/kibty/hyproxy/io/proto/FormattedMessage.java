package town.kibty.hyproxy.io.proto;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.Pair;
import lombok.*;
import org.jspecify.annotations.Nullable;
import town.kibty.hyproxy.io.proto.param.ParamValue;
import town.kibty.hyproxy.common.util.ProtocolUtil;
import town.kibty.hyproxy.common.util.VarIntUtil;

import java.util.HashMap;
import java.util.Map;

@NoArgsConstructor
@AllArgsConstructor
@ToString
@Getter
@Setter
public class FormattedMessage {
    private @Nullable String rawText;
    private @Nullable String messageId;
    private FormattedMessage @Nullable[] children;
    private @Nullable Map<String, ParamValue> params;
    private @Nullable Map<String, FormattedMessage> messageParams;
    private @Nullable String color;
    private MaybeBool bold = MaybeBool.NULL;
    private MaybeBool italic = MaybeBool.NULL;
    private MaybeBool monospace = MaybeBool.NULL;
    private MaybeBool underlined = MaybeBool.NULL;
    private @Nullable String link;
    private boolean markupEnabled;

    public static FormattedMessage deserialize(ByteBuf buf) {
        byte nullBits = buf.readByte();
        MaybeBool bold = MaybeBool.getById(buf.readByte());
        MaybeBool italic = MaybeBool.getById(buf.readByte());
        MaybeBool monospace = MaybeBool.getById(buf.readByte());
        MaybeBool underlined = MaybeBool.getById(buf.readByte());
        boolean markupEnabled = buf.readByte() != 0;

        int rawTextOffset = buf.readIntLE();
        int messageIdOffset = buf.readIntLE();
        int childrenOffset = buf.readIntLE();
        int paramsOffset = buf.readIntLE();
        int messageParamsOffset = buf.readIntLE();
        int colorOffset = buf.readIntLE();
        int linksOffset = buf.readIntLE();

        int varsOffset = buf.readerIndex();

        int readViaOffsets = 0;

        String rawText = null;
        if ((nullBits & 0x1) != 0) {
            int offset = varsOffset + rawTextOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 128);
            rawText = varString.left();
            readViaOffsets += varString.right();
        }

        String messageId = null;
        if ((nullBits & 0x2) != 0) {
            int offset = varsOffset + messageIdOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 128);
            messageId = varString.left();
            readViaOffsets += varString.right();
        }

        FormattedMessage[] children = null;
        if ((nullBits & 0x4) != 0) {
            int oldOffset = buf.readerIndex();

            int offset = varsOffset + childrenOffset;
            buf.readerIndex(offset);

            int varIntLength = VarIntUtil.length(buf, offset);
            int length = VarIntUtil.read(buf);
            if (length < 0 || length > 128) {
                throw new IllegalArgumentException("too many children in formatted message");
            }

            readViaOffsets += varIntLength;

            children = new FormattedMessage[length];

            for (int i = 0; i < length; i++) {
                int oldChildrenOffset = buf.readerIndex();
                children[i] = FormattedMessage.deserialize(buf);
                readViaOffsets += buf.readerIndex() - oldChildrenOffset;
            }

            buf.readerIndex(oldOffset);
        }

        Map<String, ParamValue> params = null;
        if ((nullBits & 0x8) != 0) {
            int oldOffset = buf.readerIndex();
            int offset = varsOffset + paramsOffset;

            buf.readerIndex(offset);
            int varIntLength = VarIntUtil.length(buf, offset);
            int length = VarIntUtil.read(buf);

            if (length < 0 || length > 128) {
                throw new IllegalArgumentException("too many params in formatted message");
            }

            readViaOffsets += varIntLength;
            params = new HashMap<>();

            for (int i = 0; i < length; i++) {
                int oldParamOffset = buf.readerIndex();
                String key = ProtocolUtil.readVarString(buf, 128);
                ParamValue value = ParamValue.deserialize(buf);

                params.put(key, value);
                readViaOffsets += buf.readerIndex() - oldParamOffset;
            }

            buf.readerIndex(oldOffset);
        }

        Map<String, FormattedMessage> messageParams = null;

        if((nullBits & 16) != 0) {
            int oldOffset = buf.readerIndex();
            int offset = varsOffset + messageParamsOffset;

            buf.readerIndex(offset);
            int varIntLength = VarIntUtil.length(buf, offset);
            int length = VarIntUtil.read(buf);

            if (length < 0 || length > 128) {
                throw new IllegalArgumentException("too many message params in formatted message");
            }

            readViaOffsets += varIntLength;
            messageParams = new HashMap<>();

            for (int i = 0; i < length; i++) {
                int oldParamOffset = buf.readerIndex();
                String key = ProtocolUtil.readVarString(buf, 128);
                FormattedMessage value = FormattedMessage.deserialize(buf);

                messageParams.put(key, value);
                readViaOffsets += buf.readerIndex() - oldParamOffset;
            }

            buf.readerIndex(oldOffset);
        }

        String color = null;
        if ((nullBits & 32) != 0) {
            int offset = varsOffset + colorOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 32);
            color = varString.left();
            readViaOffsets += varString.right();
        }

        String link = null;
        if ((nullBits & 32) != 0) {
            int offset = varsOffset + linksOffset;
            Pair<String, Integer> varString = ProtocolUtil.readVarString(buf, offset, 1024);
            link = varString.left();
            readViaOffsets += varString.right();
        }

        buf.readerIndex(varsOffset + readViaOffsets);
        return new FormattedMessage(
                rawText,
                messageId,
                children,
                params,
                messageParams,
                color,
                bold,
                italic,
                monospace,
                underlined,
                link,
                markupEnabled
        );
    }

    public void serialize(ByteBuf buf) {
        byte nullBits = 0;
        if (this.rawText != null) {
            nullBits = (byte)(nullBits | 1);
        }

        if (this.messageId != null) {
            nullBits = (byte)(nullBits | 2);
        }

        if (this.children != null) {
            nullBits = (byte)(nullBits | 4);
        }

        if (this.params != null) {
            nullBits = (byte)(nullBits | 8);
        }

        if (this.messageParams != null) {
            nullBits = (byte)(nullBits | 16);
        }

        if (this.color != null) {
            nullBits = (byte)(nullBits | 32);
        }

        if (this.link != null) {
            nullBits = (byte)(nullBits | 64);
        }

        buf.writeByte(nullBits);
        buf.writeByte(this.bold.getId());
        buf.writeByte(this.italic.getId());
        buf.writeByte(this.monospace.getId());
        buf.writeByte(this.underlined.getId());
        buf.writeByte(this.markupEnabled ? 1 : 0);

        int rawTextOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int messageIdOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int childrenOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int paramsOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int messageParamsOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int colorOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);
        int linkOffsetSlot = buf.writerIndex();
        buf.writeIntLE(-1);

        int varsOffset = buf.writerIndex();

        if (this.rawText != null) {
            buf.setIntLE(rawTextOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.rawText);
        }

        if (this.messageId != null) {
            buf.setIntLE(messageIdOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.messageId);
        }

        if (this.children != null) {
            buf.setIntLE(childrenOffsetSlot, buf.writerIndex() - varsOffset);
            VarIntUtil.write(buf, this.children.length);

            for (FormattedMessage child : this.children) {
                child.serialize(buf);
            }
        }


        if (this.params != null) {
            buf.setIntLE(paramsOffsetSlot, buf.writerIndex() - varsOffset);
            VarIntUtil.write(buf, this.params.size());

            for (Map.Entry<String, ParamValue> entry : this.params.entrySet()) {
                ProtocolUtil.writeVarString(buf, entry.getKey());
                entry.getValue().serializeWithTypeId(buf);
            }
        }

        if (this.messageParams != null) {
            buf.setIntLE(messageParamsOffsetSlot, buf.writerIndex() - varsOffset);
            VarIntUtil.write(buf, this.messageParams.size());

            for (Map.Entry<String, FormattedMessage> entry : this.messageParams.entrySet()) {
                ProtocolUtil.writeVarString(buf, entry.getKey());
                entry.getValue().serialize(buf);
            }
        }

        if (this.color != null) {
            buf.setIntLE(colorOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.color);
        }


        if (this.link != null) {
            buf.setIntLE(linkOffsetSlot, buf.writerIndex() - varsOffset);
            ProtocolUtil.writeVarString(buf, this.link);
        }
    }
}
