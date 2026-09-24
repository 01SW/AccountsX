package top.syshub.accountsx.common.accounts.impl.injector;

import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.utils.Threading;

import java.util.UUID;

public abstract class AbstractInjectorAccount extends BaseAccount {
    private final String server;

    private volatile String loginToken;

    private volatile String preferredPlayerUUID;

    public AbstractInjectorAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, AccountType type, String accountName, String avatarKey, long avatarCachedAt) {
        super(accessToken, playerName, playerUUID, type, accountName, avatarKey, avatarCachedAt);
        this.server = server;
        this.loginToken = accessToken;
        this.preferredPlayerUUID = preferredPlayerUUID;
    }

    public final String getServer() {
        return server;
    }

    public final String getLoginToken() {
        return loginToken;
    }

    public final String getPreferredPlayerUUID() {
        return preferredPlayerUUID;
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public final void setLoginProfile(String loginToken, String preferredPlayerUUID) {
        Threading.checkAccountWorkerThread();
        setLoginTokenForMigration(loginToken, preferredPlayerUUID);
    }

    /**
     * 与 {@link #setLoginProfile} 同效果但无线程检查：仅 {@code AccountRepository.fromJson}
     * 在启动加载时还原已持久化的登录态（同包外唯一调用方，protected 嫌窄、public 嫌宽，
     * 取 public 并在此注明约束）。运行时刷新仍走 {@link #setLoginProfile}。
     */
    public final void setLoginTokenForMigration(String loginToken, String preferredPlayerUUID) {
        this.loginToken = loginToken;
        this.preferredPlayerUUID = preferredPlayerUUID;
    }
}
