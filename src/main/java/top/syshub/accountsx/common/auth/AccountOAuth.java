package top.syshub.accountsx.common.auth;

import com.google.gson.JsonObject;

/**
 * 旧链路 {@code "OAuth {\u2026}"} 登录信封的编解码（P3.1 保留：账号对象的 loginToken
 * 仍是这个形状，P3.3 / 后续把 provider 换成 typed 凭据后删除）。
 */
public final class AccountOAuth {
    public static final String PREFIX = "OAuth ";

    private AccountOAuth() {
    }

    public static boolean isEnvelope(String loginToken) {
        return loginToken != null && loginToken.startsWith(PREFIX);
    }

    public static String envelope(String refreshToken, String tokenEndpoint, String clientId) {
        JsonObject oauth = new JsonObject();
        oauth.addProperty("token_endpoint", tokenEndpoint);
        oauth.addProperty("refresh_token", refreshToken);
        oauth.addProperty("client_id", clientId);
        return PREFIX + oauth;
    }
}