package town.kibty.hyproxy.io.proto;

public enum ClientType {
    GAME,
    EDITOR;


    public byte getId() {
        return (byte) this.ordinal();
    }

    public static ClientType getById(byte id) {
        return ClientType.values()[id];
    }
}
