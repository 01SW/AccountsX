package top.syshub.accountsx.common.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * OAuth 端点集（servers[].oauth）。3→4 迁移只种 {@code clientId} / {@code tokenEndpoint}；
 * device / userinfo 端点由首次探测（本类 {@link #mergedWith} 的 probe 侧）补全，逐字段谁有谁留。
 */
public record OAuthEndpoints(String clientId, String tokenEndpoint, String deviceAuthorizationEndpoint,
        String userinfoEndpoint) {
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
        if (clientId == null && tokenEndpoint == null && device == null && userinfo == null)
            return null;
        return new OAuthEndpoints(clientId, tokenEndpoint, device, userinfo);
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
                userinfoEndpoint != null ? userinfoEndpoint : other.userinfoEndpoint());
    }

    public static OAuthEndpoints merge(OAuthEndpoints a, OAuthEndpoints b) {
        if (a == null)
            return b;
        return a.mergedWith(b);
    }
}
