package top.syshub.accountsx.common.ui.form;

import java.util.Objects;

/**
 * 表单字段的稳定键，取代 "guid:as.login..." 这类魔法字符串（P3.2）。
 */
public record FieldKey(String value) {
    public static final FieldKey SERVER = new FieldKey("server");
    public static final FieldKey LOCATOR = new FieldKey("locator");
    public static final FieldKey USERNAME = new FieldKey("username");
    public static final FieldKey PASSWORD = new FieldKey("password");
    public static final FieldKey PLAYER_NAME = new FieldKey("playerName");
    public static final FieldKey ACCOUNT_NAME = new FieldKey("accountName");

    public FieldKey {
        Objects.requireNonNull(value, "value");
    }
}
