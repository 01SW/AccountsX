package top.syshub.accountsx.common.accounts.impl.injector.impl;

import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.auth.AuthServerKind;
import top.syshub.accountsx.common.net.HttpGateway;

import java.util.UUID;

public final class UnitedInjectorAccountProvider extends AbstractInjectorAccountProvider<UnitedInjectorAccountProvider.UnitedInjectorAccount> {
    public UnitedInjectorAccountProvider(HttpGateway http) {
        super("United-Injector", http);
    }

    @Override
    protected AuthServerKind serverKind() {
        // apiRoot 拼接（https://auth.mc-user.com:233/<id>）随 kind 进 AuthServerRegistry.resolveApiRoot
        return AuthServerKind.UNITED;
    }

    @Override
    // 1.16 修复：保留 accountName，使联合通行证账号在列表里显示服务器名而非类型名
    protected UnitedInjectorAccount createAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, String accountName, String avatarKey, long avatarCachedAt) {
        return new UnitedInjectorAccount(accessToken, playerName, playerUUID, server, preferredPlayerUUID, accountName, avatarKey, avatarCachedAt);
    }

    public static class UnitedInjectorAccount extends AbstractInjectorAccount {
        public UnitedInjectorAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, String accountName, String avatarKey, long avatarCachedAt) {
            super(accessToken, playerName, playerUUID, server, preferredPlayerUUID, AccountType.UNITED_INJECTOR, accountName, avatarKey, avatarCachedAt);
        }
    }
}
