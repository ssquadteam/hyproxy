package ac.eva.hyproxy.common.util;

import lombok.experimental.UtilityClass;

import java.security.SecureRandom;

@UtilityClass
public class RandomUtil {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    public String generateSecureRandomString(int length) {
        final String chars = "AaBbCcDdEeFfGgHhIiJjKkLlMmNnOoPpQqRrSsTtUuVvWwXxYyZz1234567890";
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; i++) {
            builder.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }
        return builder.toString();
    }
}
