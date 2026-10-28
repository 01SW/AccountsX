package top.syshub.accountsx.common.accounts.impl.microsoft;

import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.accounts.BaseAccount;

import java.util.UUID;

public final class MicrosoftAccount extends BaseAccount {
    private volatile String microsoftAccountAccessToken;

    private volatile String microsoftAccountRefreshToken;

    public MicrosoftAccount(String accessToken, String playerName, UUID playerUUID, String microsoftAccountAccessToken, String microsoftAccountRefreshToken, String avatarKey, long avatarCachedAt) {
        super(accessToken, playerName, playerUUID, AccountType.MICROSOFT, null, avatarKey, avatarCachedAt);
        this.microsoftAccountAccessToken = microsoftAccountAccessToken;
        this.microsoftAccountRefreshToken = microsoftAccountRefreshToken;
    }

    /**
     * 带令牌过期时刻的构造器：登录链路拿到 {@code expires_in} 时用它，
     * 下次启动即可在到期前跳过整条 MSA → Xbox → Minecraft 换票。
     */
    public MicrosoftAccount(String accessToken, String playerName, UUID playerUUID, String microsoftAccountAccessToken, String microsoftAccountRefreshToken, String avatarKey, long avatarCachedAt, long expiresAt) {
        this(accessToken, playerName, playerUUID, microsoftAccountAccessToken, microsoftAccountRefreshToken, avatarKey, avatarCachedAt);
        setTokenExpiryForLoad(expiresAt);
    }

    public String getMicrosoftAccountAccessToken() {
        return microsoftAccountAccessToken;
    }

    public String getMicrosoftAccountRefreshToken() {
        return microsoftAccountRefreshToken;
    }

    public void setMicrosoftAccountToken(String microsoftAccountAccessToken, String microsoftAccountRefreshToken) {
        this.microsoftAccountAccessToken = microsoftAccountAccessToken;
        this.microsoftAccountRefreshToken = microsoftAccountRefreshToken;
    }
}
