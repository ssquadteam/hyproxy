package ac.eva.hyproxy.io.proto;

import org.jspecify.annotations.Nullable;

public enum NetworkChannel {
    DEFAULT,
    CHUNKS,
    WORLD_MAP,
    VOICE;

    public static @Nullable NetworkChannel fromStreamId(long streamId) {
        if (streamId == 0) {
            return NetworkChannel.DEFAULT;
        }

        NetworkChannel[] values = NetworkChannel.values();
        int index = Math.toIntExact((streamId >> 2) + 1);

        if (index >= values.length) {
            return null;
        }

        return values[index];
    }
}
