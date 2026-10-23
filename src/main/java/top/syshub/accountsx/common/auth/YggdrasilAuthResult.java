package top.syshub.accountsx.common.auth;

/** 两种 injector 登录/刷新流程的统一产物（P3.1）。avatar 字段为 null/0 表示未取到（可重建，不阻塞）。 */
public record YggdrasilAuthResult(String accessToken, String playerName, String playerUuid,
        String refreshToken, String tokenEndpoint, String clientId,
        String avatarKey, long avatarCachedAt) {
}
