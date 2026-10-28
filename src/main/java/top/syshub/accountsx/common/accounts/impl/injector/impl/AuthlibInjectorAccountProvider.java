package top.syshub.accountsx.common.accounts.impl.injector.impl;

import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.auth.server.AuthServerKind;
import top.syshub.accountsx.common.net.HttpGateway;

import java.util.UUID;

public class AuthlibInjectorAccountProvider extends AbstractInjectorAccountProvider<AuthlibInjectorAccountProvider.AuthlibInjectorAccount> {
    public AuthlibInjectorAccountProvider(HttpGateway http) {
        super("Authlib-Injector", http);
    }

    @Override
    protected AuthServerKind serverKind() {
        return AuthServerKind.YGGDRASIL;
    }

    @Override
    protected AuthlibInjectorAccount createAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, String accountName, String avatarKey, long avatarCachedAt) {
        return new AuthlibInjectorAccount(accessToken, playerName, playerUUID, server, preferredPlayerUUID, accountName, avatarKey, avatarCachedAt);
    }

    public static class AuthlibInjectorAccount extends AbstractInjectorAccount {
        public AuthlibInjectorAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, String accountName, String avatarKey, long avatarCachedAt) {
            super(accessToken, playerName, playerUUID, server, preferredPlayerUUID, AccountType.AUTHLIB_INJECTOR, accountName, avatarKey, avatarCachedAt);
        }
    }
}
