package top.syshub.accountsx.common.auth;

/**
 * 两种 injector 登录/刷新流程的统一产物（P3.1）。avatar 字段为 null/0 表示未取到（可重建，不阻塞）；
 * {@code expiresAt} 是 OAuth 令牌的过期时刻（epoch 毫秒，取自 token 响应的 expires_in），
 * 会话令牌没有这个概念，恒为 0=未知（有效性靠 /authserver/validate 确认）。
 */
public record YggdrasilAuthResult(String accessToken, String playerName, String playerUuid,
        String refreshToken, String tokenEndpoint, String clientId, String clientToken,
        String avatarKey, long avatarCachedAt, long expiresAt) {
}