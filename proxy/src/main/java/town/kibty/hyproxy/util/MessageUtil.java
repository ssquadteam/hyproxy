package town.kibty.hyproxy.util;

import lombok.experimental.UtilityClass;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Colors;
import town.kibty.hyproxy.message.Message;

import javax.annotation.Nonnull;
import java.awt.*;

@UtilityClass
public class MessageUtil {
    public AttributedString toAnsiString(Message message) {
        AttributedStyle style = AttributedStyle.DEFAULT;
        String color = message.getColor();
        if (color != null) {
            style = hexToStyle(color);
        }

        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(style).append(message.getAnsiMessage());

        for (Message child : message.getChildren()) {
            sb.append(toAnsiString(child));
        }

        return sb.toAttributedString();
    }

    public AttributedStyle hexToStyle(@Nonnull String str) {
        Color color = ColorUtil.parseColor(str);
        if (color == null) {
            return AttributedStyle.DEFAULT;
        } else {
            int colorId = Colors.roundRgbColor(color.getRed(), color.getGreen(), color.getBlue(), 256);
            return AttributedStyle.DEFAULT.foreground(colorId);
        }
    }
}
