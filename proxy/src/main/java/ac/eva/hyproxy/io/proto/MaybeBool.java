package ac.eva.hyproxy.io.proto;

public enum MaybeBool {
    NULL,
    FALSE,
    TRUE;

    public byte getId() {
        return (byte) this.ordinal();
    }

    public static MaybeBool getById(byte id) {
        return MaybeBool.values()[id];
    }
    public static MaybeBool fromBool(boolean bool) {
        return bool ? TRUE : FALSE;
    }
}
