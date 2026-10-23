package top.syshub.accountsx.common.accounts.impl.offline;

import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.AccountUUID;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.ui.Memory;
import top.syshub.accountsx.common.ui.form.FieldKey;
import top.syshub.accountsx.common.ui.form.FormAction;
import top.syshub.accountsx.common.ui.form.FormField;
import top.syshub.accountsx.common.ui.form.FormSpec;
import top.syshub.accountsx.common.ui.form.ValidationOutcome;
import java.util.List;

import java.util.Objects;
import java.util.UUID;

public class OfflineAccountProvider implements AccountProvider<OfflineAccount> {
    private static final String GUID_PLAYER_NAME = FieldKey.PLAYER_NAME.value();
    private static final String GUID_PLAYER_UUID = new FieldKey("playerUuid").value();

    // 离线账号不发起网络请求；构造器接收网关仅为统一所有 provider 的构造形态（P1.3）。
    // 即便不使用，也校验注入的网关非空，与依赖注入契约保持一致。
    public OfflineAccountProvider(HttpGateway http) {
        Objects.requireNonNull(http, "http gateway");
    }

    @Override
    public AccountContext createAccountContext(OfflineAccount account) {
        return null;
    }

    @Override
    public FormSpec form() {
        return new FormSpec("accountsx.account.general.login", List.of(
                new FormField.Text(FieldKey.PLAYER_NAME, "accountsx.account.objects.player_name", false, null),
                new FormField.Text(new FieldKey("playerUuid"), "accountsx.account.objects.player_uuid", false, null)),
                List.of(
                        new FormAction("login", "accountsx.account.general.login", true),
                        new FormAction("close", "accountsx.general.action.close", false)));
    }

    @Override
    public ValidationOutcome validate(Memory memory) throws IllegalArgumentException {
        String playerName = memory.get(GUID_PLAYER_NAME, String.class);
        memory.set(GUID_PLAYER_NAME, playerName);

        String playerUUIDString = memory.get(GUID_PLAYER_UUID, String.class);
        if (playerUUIDString == null || playerUUIDString.isEmpty()) {
            memory.set(GUID_PLAYER_UUID, AccountUUID.ofPlayerName(playerName));
        } else {
            try {
                memory.set(GUID_PLAYER_UUID, AccountUUID.parse(playerUUIDString));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Cannot parse current UUID: " + playerUUIDString);
            }
        }

        return ValidationOutcome.CLOSE_IMMEDIATELY;
    }

    @Override
    public OfflineAccount login(Memory memory) {
        return new OfflineAccount(
                UUID.randomUUID().toString().replace("-", ""),
                memory.get(GUID_PLAYER_NAME, String.class),
                memory.get(GUID_PLAYER_UUID, UUID.class)
        );
    }

    @Override
    public void refresh(OfflineAccount account) {
        BaseAccount.AccountStorage s = account.getAccountStorage();

        account.setProfile(
                UUID.randomUUID().toString().replace("-", ""),
                s.getPlayerName(),
                s.getPlayerUUID()
        );
    }
}
