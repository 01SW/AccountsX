package top.syshub.accountsx.common.storage.migration;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 2→3 {@code SECURITY_STORAGE}：账号载荷搬到 {@code ~/.accountsx/<id>.json}，
 * 实例配置只留 version + id（历史行为，保留是为了 v2 能一路升到 v4）。
 * 全程走 {@link MigrationContext#fs()} 与注入目录，测试不落真实磁盘。
 */
public final class SecurityStorage implements Migration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityStorage.class);

    private final Gson gson;

    public SecurityStorage(Gson gson) {
        this.gson = gson;
    }

    @Override
    public int fromVersion() {
        return 2;
    }

    @Override
    public int toVersion() {
        return 3;
    }

    @Override
    public JsonObject apply(JsonObject config, MigrationContext ctx) throws IOException {
        JsonElement idElement = config.get("id");
        boolean hasId = idElement != null && idElement.isJsonPrimitive()
                && idElement.getAsJsonPrimitive().isString() && !idElement.getAsString().isBlank();
        String id = hasId ? idElement.getAsString() : UUID.randomUUID().toString();
        config.addProperty("id", id);

        JsonArray accounts = config.get("accounts") instanceof JsonArray a ? a : new JsonArray();
        Path home = ctx.legacyHomeDir();
        Files.createDirectories(home);
        Files.writeString(home.resolve(id + ".json"), gson.toJson(accounts), StandardCharsets.UTF_8);
        config.remove("accounts");

        LOGGER.info("已将 {} 个账号搬到旧载荷文件（id={}）。", accounts.size(), id);
        config.addProperty("version", toVersion());
        return config;
    }
}