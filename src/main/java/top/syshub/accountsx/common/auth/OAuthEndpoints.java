package top.syshub.accountsx.common.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * OAuth 端点集（servers[].oauth）。3→4 迁移只种 {@code clientId} / {@code tokenEndpoint}；
 * device / userinfo / issuer / jwks 端点由探测（本类 {@link #mergedWith} 的 probe 侧）补全，逐字段谁有谁留。
 *
 * <p>{@code issuer} 与 {@code jwksUri} 来自 YGGC（Yggdrasil Connect）的 openid 配置，只用于校验
 * id_token 的签发者与签名密钥——少了它们就无从确认「这张令牌是不是这台服务器发给我的」。</p>
 */
public record OAuthEndpoints(String clientId, String tokenEndpoint, String deviceAuthorizationEndpoint,
        String userinfoEndpoint, String issuer, String jwksUri) {

    /** 只关心四个端点时的便捷构造（迁移、测试与旧调用点用不到签发信息）。 */
    public OAuthEndpoints(String clientId, String tokenEndpoint, String deviceAuthorizationEndpoint,
            String userinfoEndpoint) {
        this(clientId, tokenEndpoint, deviceAuthorizationEndpoint, userinfoEndpoint, null, null);
    }

    static String str(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    public static OAuthEndpoints fromJson(JsonObject jo) {
        if (jo == null)
            return null;
        String clientId = str(jo.get("clientId"));
        String tokenEndpoint = str(jo.get("tokenEndpoint"));
        String device = str(jo.get("deviceAuthorizationEndpoint"));
        String userinfo = str(jo.get("userinfoEndpoint"));
        String issuer = str(jo.get("issuer"));
        String jwks = str(jo.get("jwksUri"));
        if (clientId == null && tokenEndpoint == null && device == null && userinfo == null
                && issuer == null && jwks == null)
            return null;
        return new OAuthEndpoints(clientId, tokenEndpoint, device, userinfo, issuer, jwks);
    }

    public JsonObject toJson() {
        JsonObject jo = new JsonObject();
        if (clientId != null)
            jo.addProperty("clientId", clientId);
        if (tokenEndpoint != null)
            jo.addProperty("tokenEndpoint", tokenEndpoint);
        if (deviceAuthorizationEndpoint != null)
            jo.addProperty("deviceAuthorizationEndpoint", deviceAuthorizationEndpoint);
        if (userinfoEndpoint != null)
            jo.addProperty("userinfoEndpoint", userinfoEndpoint);
        if (issuer != null)
            jo.addProperty("issuer", issuer);
        if (jwksUri != null)
            jo.addProperty("jwksUri", jwksUri);
        return jo;
    }

    /** 已有字段优先保留（迁移种下的 tokenEndpoint/clientId 不被探测覆盖为 null）。 */
    public OAuthEndpoints mergedWith(OAuthEndpoints other) {
        if (other == null)
            return this;
        return new OAuthEndpoints(
                clientId != null ? clientId : other.clientId(),
                tokenEndpoint != null ? tokenEndpoint : other.tokenEndpoint(),
                deviceAuthorizationEndpoint != null ? deviceAuthorizationEndpoint : other.deviceAuthorizationEndpoint(),
                userinfoEndpoint != null ? userinfoEndpoint : other.userinfoEndpoint(),
                issuer != null ? issuer : other.issuer(),
                jwksUri != null ? jwksUri : other.jwksUri());
    }

    public static OAuthEndpoints merge(OAuthEndpoints a, OAuthEndpoints b) {
        if (a == null)
            return b;
        return a.mergedWith(b);
    }
}
