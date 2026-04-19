package ac.eva.hyproxy.util;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

import java.awt.*;
import java.util.regex.Pattern;

// this entire thing is kind of stupid, i dont like using java.awt.Color, but vanilla does it and i don't feel like implementing my own thing.
@UtilityClass
public class ColorUtil {

    public static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^\\s*#([0-9a-fA-F]{3}){1,2}\\s*$");
    public static final Pattern RGB_COLOR_PATTERN = Pattern.compile("^\\s*rgb\\((\\s*[0-9]{1,3}\\s*,){2}\\s*[0-9]{1,3}\\s*\\)\\s*$");


    public String colorToHex(Color color) {
        if (color == null) {
            return "#FFFFFF";
        }

        int argb = color.getRGB();
        int rgb = argb & 16777215;

        return toHexString(rgb);
    }

    public String toHexString(int rgb) {
        String hexString = Integer.toHexString(rgb);
        return "#" + "0".repeat(6 - hexString.length()) + hexString;
    }

    public @Nullable Color parseColor(String stringValue) {
        if (HEX_COLOR_PATTERN.matcher(stringValue).matches()) {
            return hexStringToColor(stringValue);
        } else {
            return RGB_COLOR_PATTERN.matcher(stringValue).matches() ? rgbStringToColor(stringValue) : null;
        }
    }

    public @Nullable Color hexStringToColor(String color) {
        try {
            return Color.decode(color);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public Color rgbStringToColor(String color) {
        color = color.trim();
        if (color.startsWith("rgb(") && color.charAt(color.length() - 1) == ')') {
            color = color.substring(4, color.length() - 1);
            String[] channels = color.split(",");
            int channelLength = channels.length;
            if (channelLength != 3) {
                throw new IllegalArgumentException("rgb() but contain all 3 channels; r, g and b");
            } else {
                byte red = (byte)Integer.parseInt(channels[0].trim());
                byte green = (byte)Integer.parseInt(channels[1].trim());
                byte blue = (byte)Integer.parseInt(channels[2].trim());
                return new Color(red, green, blue);
            }
        } else {
            throw new IllegalArgumentException("color must start with 'rgb(' and end with ')'");
        }
    }
}
