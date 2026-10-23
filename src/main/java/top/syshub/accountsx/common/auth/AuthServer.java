package top.syshub.accountsx.common.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 认证服务器一等实体（P3.1 / 待办 2）。磁盘形状即 v4 {@code servers[]} 条目：
 * {@code type}（kind）+ {@code apiRoot}（解析后的 API 根，账号的 {@code server} 字段引用它）。
 *
 * <p>{@code mojang} / {@code offline} 两条固定条目没有 apiRoot：内存里用 kind 字符串作合成键
 * （08 schema 里它们就是无 apiRoot 的占位条目），写回时不补 apiRoot，避免污染迁移形状。</p>
 */
public record AuthServer(String apiRoot, AuthServerKind kind, String displayName, String locator,
        OAuthEndpoints oauth, long cachedAt) {
    public AuthServer {
        Objects.requireNonNull(kind, "kind");
    }

    /** 固定条目（无 apiRoot）的内存键。 */
    public String key() {
        return apiRoot != null ? apiRoot : kind.v4type();
    }

    static String str(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    static long lng(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isNumber() ? p.getAsLong() : 0L;
    }

    public static AuthServer fromJson(JsonObject jo) {
        if (jo == null)
            return null;
        String type = str(jo.get("type"));
        if (type == null)
            type = str(jo.get("kind"));
        AuthServerKind kind = AuthServerKind.fromV4(type);
        if (kind == null)
            return null;
        String apiRoot = str(jo.get("apiRoot"));
        if (apiRoot == null)
            apiRoot = str(jo.get("server"));
        if (apiRoot == null && !kind.builtin())
            return null;
        OAuthEndpoints oauth = jo.get("oauth") instanceof JsonObject o ? OAuthEndpoints.fromJson(o) : null;
        return new AuthServer(apiRoot, kind, str(jo.get("displayName")), str(jo.get("locator")), oauth,
                lng(jo.get("cachedAt")));
    }

    public JsonObject toJson() {
        JsonObject jo = new JsonObject();
        jo.addProperty("type", kind.v4type());
        // 固定条目（mojang / offline）保持迁移形状：不写合成 apiRoot
        if (apiRoot != null)
            jo.addProperty("apiRoot", apiRoot);
        if (locator != null)
            jo.addProperty("locator", locator);
        if (displayName != null)
            jo.addProperty("displayName", displayName);
        if (oauth != null)
            jo.add("oauth", oauth.toJson());
        if (cachedAt != 0L)
            jo.addProperty("cachedAt", cachedAt);
        return jo;
    }

    /** 条目在内存里的键（apiRoot 或固定条目的 kind 字符串）——persist 时按它对上原条目。 */
    public static String keyOf(JsonObject jo) {
        String apiRoot = str(jo.get("apiRoot"));
        if (apiRoot != null)
            return apiRoot;
        String type = str(jo.get("type"));
        return type != null ? type : "";
    }

    public static List<AuthServer> readAll(JsonArray servers) {
        List<AuthServer> out = new ArrayList<>();
        if (servers == null)
            return out;
        for (JsonElement e : servers) {
            if (e instanceof JsonObject jo) {
                AuthServer server = fromJson(jo);
                if (server != null)
                    out.add(server);
            }
        }
        return out;
    }
}