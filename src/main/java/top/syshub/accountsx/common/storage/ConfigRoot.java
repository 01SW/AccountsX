package top.syshub.accountsx.common.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.NonNull;

/**
 * v4 配置根（version / servers / accounts）。
 *
 * <p>刻意以 {@link JsonObject} 为底座而非强类型 record：未知字段原样保留并回写，
 * 避免"新版本写入 → 旧版本读取 → 旧版本保存"链路上 Gson 丢字段；typed 视图
 * 由上层（P2.4 {@code AccountRepository}、P3 {@code AuthServerRegistry}）在
 * {@link #raw()} 之上构建，存储层不提前绑定账号 / 服务器模型。</p>
 */
public record ConfigRoot(JsonObject raw) {
    public ConfigRoot(JsonObject raw) {
        this.raw = raw;
        if (!raw.has("version"))
            raw.addProperty("version", ConfigStore.CURRENT_VERSION);
        // servers / accounts 恒在：消费方不必每处判空
        ensureArray("servers");
        ensureArray("accounts");
    }

    public static ConfigRoot empty(int version) {
        JsonObject raw = new JsonObject();
        raw.addProperty("version", version);
        raw.add("servers", new JsonArray());
        raw.add("accounts", new JsonArray());
        return new ConfigRoot(raw);
    }

    private void ensureArray(String key) {
        JsonElement e = raw.get(key);
        if (e == null || e.isJsonNull())
            raw.add(key, new JsonArray());
    }

    public int version() {
        return raw.get("version").getAsInt();
    }

    public void setVersion(int version) {
        raw.addProperty("version", version);
    }

    public JsonArray servers() {
        return raw.getAsJsonArray("servers");
    }

    public JsonArray accounts() {
        return raw.getAsJsonArray("accounts");
    }

    /**
     * 整个文件（含未知字段）。迁移与上层仓储直接操作它。
     */
    @Override
    public JsonObject raw() {
        return raw;
    }

    /**
     * 严禁输出 token：本对象含明文凭据（AGENTS.md 安全约束），toString 只暴露结构与规模。
     */
    @Override
    @NonNull
    public String toString() {
        return "ConfigRoot{version=" + version()
            + ", servers=" + servers().size()
            + ", accounts=" + accounts().size() + "}";
    }
}
