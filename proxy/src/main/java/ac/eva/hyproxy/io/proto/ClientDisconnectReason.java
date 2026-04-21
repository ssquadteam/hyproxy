package ac.eva.hyproxy.io.proto;

public enum ClientDisconnectReason {
    PLAYER_LEAVE,
    PLAYER_ABORT,
    USER_LEAVE,
    CRASH;

    public byte getId() {
        return (byte) this.ordinal();
    }
    public static ClientDisconnectReason getById(byte id) {
        return ClientDisconnectReason.values()[id];
    }
}
