package ac.eva.hyproxy.io.proto;

public enum DisconnectType {
    DISCONNECT,
    CRASH;

    public byte getId() {
        return (byte) this.ordinal();
    }

    public static DisconnectType getById(byte id) {
        return DisconnectType.values()[id];
    }
}
