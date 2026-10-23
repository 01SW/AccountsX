package top.syshub.accountsx.common.auth;

/** 服务器 kind；v4 字符串与 servers[].type / accounts[].type 共用（见 08 schema）。 */
public enum AuthServerKind {
    MOJANG("mojang"),
    OFFLINE("offline"),
    YGGDRASIL("yggdrasil"),
    UNITED("united");

    private final String v4type;

    AuthServerKind(String v4type) {
        this.v4type = v4type;
    }

    public String v4type() {
        return v4type;
    }

    /** mojang / offline 是固定条目，无 apiRoot、不探测。 */
    public boolean builtin() {
        return this == MOJANG || this == OFFLINE;
    }

    public static AuthServerKind fromV4(String type) {
        if (type == null)
            return null;
        for (AuthServerKind kind : values()) {
            if (kind.v4type.equals(type))
                return kind;
        }
        return null;
    }
}
