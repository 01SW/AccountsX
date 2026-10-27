package top.syshub.accountsx.common.accounts.impl.offline;

import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.AccountUUID;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.net.HttpGateway;

import java.util.Objects;
import java.util.UUID;

public class OfflineAccountProvider implements AccountProvider<OfflineAccount> {
    // 离线账号不发起网络请求；构造器接收网关仅为统一所有 provider 的构造形态（P1.3）。
    // 即便不使用，也校验注入的网关非空，与依赖注入契约保持一致。
    public OfflineAccountProvider(HttpGateway http) {
        Objects.requireNonNull(http, "http gateway");
    }

    @Override
    public AccountContext createAccountContext(OfflineAccount account) {
        return null;
    }

    /**
     * 离线建号：玩家名必填，UUID 留空时按玩家名推导（离线 UUID 的标准算法）。
     *
     * @throws IllegalArgumentException 玩家名为空，或 UUID 无法解析
     */
    public OfflineAccount login(String playerName, String playerUuid) {
        if (playerName == null || playerName.isEmpty()) {
            throw new IllegalArgumentException("Player name is required.");
        }
        UUID uuid = playerUuid == null || playerUuid.isEmpty()
                ? AccountUUID.ofPlayerName(playerName)
                : AccountUUID.parse(playerUuid);
        return new OfflineAccount(UUID.randomUUID().toString().replace("-", ""), playerName, uuid);
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
