package top.syshub.accountsx.common.ui.list;

import top.syshub.accountsx.common.ui.AvatarHandle;

import java.util.Objects;

/**
 * 账号列表的一行（P3.2）。
 */
public record AccountRow(
        String accountId,
        String primaryText,
        String secondaryText,
        String stateText,
        AvatarHandle avatar,
        boolean removable,
        boolean movable) {
    public AccountRow {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(primaryText, "primaryText");
        Objects.requireNonNull(secondaryText, "secondaryText");
        Objects.requireNonNull(stateText, "stateText");
    }
}
