package top.syshub.accountsx.common.storage.migration;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.common.utils.image.AvatarCache;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 3→4 {@code UNIFY_AND_DECOUPLE}（方案 C，见 REFACTORING/08-config-schema.md）：
 * 从"实例配置 {@code id} 索引 + {@code ~/.accountsx/<id>.json} 载荷"收敛为全局单文件。
 *
 * <p>方案 C 要点：唯一数据来源是<b>当前实例</b> {@code id} 指向的载荷；
 * {@code ~/.accountsx/} 下其余 {@code *.json} 是其他实例的账号集，一律不动
 * （未更新模组的实例还要用）。写入是<b>追加语义</b>：全局文件已存在时逐条并入、
 * 按 {@code (type, playerUuid, server)} 去重，已存在的跳过并记 warn。</p>
 *
 * <p>顺序刻意安排为"先落盘全局文件，再删载荷、改名实例配置"：载荷删除发生在
 * 全局文件原子写成功之后，崩溃窗口内数据最多重复（重跑由去重兜底），不会丢。
 * 幂等性：载荷已删 / 实例配置已改名时再跑一次只会并 0 条。</p>
 *
 * <p>迁移过程中还会：反推 servers（含固定 mojang / offline 两条）、拆
 * {@code "OAuth {\u2026}"} 登录信封为结构化 credentials（解析失败降级为"需重登"，
 * 保留账号条目）、把旧 base64 头像外移到 {@code ~/.cache/accountsx/avatars/}（D4，
 * 失败不阻塞——头像可重建）。</p>
 */
public final class UnifyAndDecouple implements Migration {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnifyAndDecouple.class);
    private static final String OAUTH_PREFIX = "OAuth ";
    private static final String HEX = "0123456789abcdef";

    private final Gson gson;

    public UnifyAndDecouple(Gson gson) {
        this.gson = gson;
    }

    @Override
    public int fromVersion() {
        return 3;
    }

    @Override
    public int toVersion() {
        return 4;
    }

    /** 转换结果：v4 账号 + 它反推出的 server 实体信息（非 injector 类型为 null）。 */
    private record Converted(JsonObject account,
                             String serverType,
                             String apiRoot,
                             String displayName,
                             JsonObject oauthSeed) {
    }

    @Override
    public JsonObject apply(JsonObject config, MigrationContext ctx) throws Exception {
        String id = requireId(config);
        JsonArray legacy = readPayload(ctx, id);

        // 追加语义：全局文件已是 v4（其他实例先迁过）则以它为底；
        // v<4（load() 触发时它就是被迁移文档本身）或不存在则新起文档，只继承输入文档的未知字段。
        JsonObject existing = readExistingGlobal(ctx);
        JsonObject out;
        JsonArray outAccounts;
        JsonArray outServers;
        if (existing != null) {
            out = existing;
            outAccounts = existing.get("accounts") instanceof JsonArray a ? a : new JsonArray();
            outServers = existing.get("servers") instanceof JsonArray a ? a : new JsonArray();
        } else {
            out = new JsonObject();
            copyUnknownFields(config, out);
            outAccounts = new JsonArray();
            outServers = new JsonArray();
        }

        Set<String> seen = new HashSet<>();
        for (JsonElement e : outAccounts)
            if (e instanceof JsonObject jo)
                seen.add(dedupKey(jo));

        int added = 0;
        int skipped = 0;
        for (JsonElement element : legacy) {
            if (!(element instanceof JsonObject account)) {
                LOGGER.warn("旧载荷含非对象条目，已忽略。");
                continue;
            }
            Converted converted = toV4(account, ctx);
            if (converted == null)
                continue;
            if (converted.serverType() != null)
                ensureServer(outServers, converted);
            String key = dedupKey(converted.account());
            if (!seen.add(key)) {
                skipped++;
                LOGGER.warn("账号已存在（type={}, server={}），跳过并入。",
                        str(converted.account().get("type")), str(converted.account().get("server")));
                continue;
            }
            outAccounts.add(converted.account());
            added++;
        }
        LOGGER.info("并入 {} 个账号，其中 {} 个已存在被跳过。", added, skipped);

        ensureServerKind(outServers, "mojang");
        ensureServerKind(outServers, "offline");

        out.add("accounts", outAccounts);
        out.add("servers", outServers);
        out.addProperty("version", toVersion());

        writeGlobal(out, ctx);

        cleanupLegacy(ctx, id);
        return out;
    }

    // ── 输入侧 ────────────────────────────────────────────────────

    private static String requireId(JsonObject config) {
        String id = str(config.get("id"));
        if (id == null || id.isBlank())
            throw new IllegalStateException("v3 配置缺少 id，无法定位账号载荷；拒绝迁移以保护数据。");
        return id;
    }

    private JsonArray readPayload(MigrationContext ctx, String id) throws IOException {
        Path payload = ctx.legacyHomeDir().resolve(id + ".json");
        if (!Files.isRegularFile(payload)) {
            LOGGER.info("旧载荷文件不存在（实例从未添加过账号，或已迁移过）：{}", payload);
            return new JsonArray();
        }
        try (Reader reader = Files.newBufferedReader(payload, StandardCharsets.UTF_8)) {
            // 注意：必须用裸 JsonParser 读——注入的 gson 可能带 UUIDTypeAdapter 等，
            // 会在 JsonElement 目标下抛错（JsonElement 不需要任何适配器）。
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || parsed.isJsonNull())
                return new JsonArray();
            if (!(parsed instanceof JsonArray accounts))
                throw new IllegalStateException("旧载荷不是 JSON 数组，拒绝迁移以保护数据: " + payload);
            return accounts;
        } catch (JsonParseException e) {
            // 载荷读不懂就抛：宁可让存储层备份降级，也不删掉一个没成功并入全局的载荷文件。
            throw new IllegalStateException("旧载荷解析失败: " + payload, e);
        }
    }

    private JsonObject readExistingGlobal(MigrationContext ctx) throws IOException {
        Path file = ctx.globalConfigFile();
        if (!Files.isRegularFile(file))
            return null;
        JsonElement parsed;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            parsed = JsonParser.parseReader(reader);
        } catch (JsonParseException e) {
            throw new IllegalStateException("全局配置文件已存在但解析失败，拒绝覆写: " + file, e);
        }
        if (!(parsed instanceof JsonObject root))
            throw new IllegalStateException("全局配置文件不是 JSON object，拒绝覆写: " + file);
        int version = root.get("version") instanceof JsonPrimitive p && p.isNumber() ? p.getAsInt() : -1;
        if (version > toVersion())
            throw new IllegalStateException("全局配置版本 " + version + " 高于迁移目标，拒绝覆写。");
        return version == toVersion() ? root : null;
    }

    private static void copyUnknownFields(JsonObject from, JsonObject to) {
        for (String key : from.keySet()) {
            if (key.equals("version") || key.equals("id") || key.equals("accounts"))
                continue;
            to.add(key, from.get(key));
        }
    }

    // ── 单账号转换 ────────────────────────────────────────────────

    private Converted toV4(JsonObject legacy, MigrationContext ctx) {
        String legacyType = str(legacy.get("type"));
        String type = switch (legacyType == null ? "" : legacyType) {
            case "offline" -> "offline";
            case "microsoft" -> "microsoft";
            case "injector.authlib-injector" -> "yggdrasil";
            case "injector.united" -> "united";
            default -> null;
        };
        if (type == null) {
            LOGGER.warn("旧载荷含未知账号类型 '{}'，已跳过该账号。", legacyType);
            return null;
        }

        JsonObject account = new JsonObject();
        account.addProperty("type", type);

        JsonObject storage = legacy.get("storage") instanceof JsonObject s ? s : new JsonObject();
        String name = str(storage.get("playerName"));
        String uuid = normalizeUuid(str(storage.get("playerUUID")));
        if (name != null || uuid != null) {
            JsonObject profile = new JsonObject();
            if (name != null)
                profile.addProperty("name", name);
            if (uuid != null)
                profile.addProperty("uuid", uuid);
            account.add("profile", profile);
        }

        String accessToken = str(storage.get("accessToken"));
        String serverType = null;
        String apiRoot = null;
        String displayName = null;
        JsonObject oauthSeed = null;
        JsonObject credentials = null;

        boolean injector = type.equals("yggdrasil") || type.equals("united");
        if (injector) {
            apiRoot = str(legacy.get("server"));
            if (apiRoot == null || apiRoot.isBlank()) {
                LOGGER.warn("injector 账号缺少 server 字段，无法归入服务器，已跳过该账号。");
                return null;
            }
            account.addProperty("server", apiRoot);
            serverType = type;
            displayName = str(legacy.get("accountName"));

            String loginToken = str(legacy.get("loginToken"));
            if (loginToken != null && loginToken.startsWith(OAUTH_PREFIX)) {
                JsonObject envelope = parseEnvelope(loginToken);
                if (envelope == null) {
                    // 降级："需要重新登录"——清空 credentials 但保留账号条目与 profile（08 §3→4.4）
                    LOGGER.warn("OAuth 登录信封解析失败，账号 {} 需要重新登录（条目已保留）。", name);
                } else {
                    credentials = new JsonObject();
                    credentials.addProperty("oauth", true);
                    if (accessToken != null)
                        credentials.addProperty("accessToken", accessToken);
                    credentials.addProperty("refreshToken", str(envelope.get("refresh_token")));
                    if (type.equals("yggdrasil")) {
                        // token_endpoint / client_id 归服务器：先种进 servers 的 oauth 块，
                        // 首次探测（P3.1）再从 OIDC 元数据补全 device/userinfo 端点。
                        oauthSeed = new JsonObject();
                        oauthSeed.addProperty("clientId", str(envelope.get("client_id")));
                        oauthSeed.addProperty("tokenEndpoint", str(envelope.get("token_endpoint")));
                    }
                }
            } else if (accessToken != null || loginToken != null) {
                credentials = new JsonObject();
                credentials.addProperty("accessToken", accessToken != null ? accessToken : loginToken);
            }
        } else if (type.equals("microsoft")) {
            String msRefresh = str(legacy.get("microsoftAccountRefreshToken"));
            if (accessToken != null || msRefresh != null) {
                credentials = new JsonObject();
                if (accessToken != null)
                    credentials.addProperty("accessToken", accessToken);
                if (msRefresh != null)
                    credentials.addProperty("refreshToken", msRefresh);
            }
            // microsoftAccountAccessToken（XBL/XSTS 链中间 token）不进 v4 模型：
            // refreshToken 足以重建它，留着只会扩大明文 token 落地面。
        }

        if (credentials != null && credentials.size() > 0)
            account.add("credentials", credentials);

        String avatarKey = migrateAvatar(legacy, ctx);
        if (avatarKey != null)
            account.addProperty("avatarKey", avatarKey);

        return new Converted(account, serverType, apiRoot, displayName, oauthSeed);
    }

    private static JsonObject parseEnvelope(String loginToken) {
        try {
            if (!(JsonParser.parseString(loginToken.substring(OAUTH_PREFIX.length())) instanceof JsonObject envelope))
                return null;
            for (String field : List.of("token_endpoint", "refresh_token", "client_id")) {
                if (str(envelope.get(field)) == null) {
                    LOGGER.warn("OAuth 登录信封缺字段 {}。", field);
                    return null;
                }
            }
            return envelope;
        } catch (RuntimeException e) {
            LOGGER.debug("OAuth 登录信封不是合法 JSON。", e);
            return null;
        }
    }

    /** 已有 avatarKey 直接沿用；旧 base64 头像解码后按内容哈希落盘（D4），失败不阻塞迁移。 */
    private static String migrateAvatar(JsonObject legacy, MigrationContext ctx) {
        String existing = str(legacy.get("avatarKey"));
        if (existing != null && !existing.isBlank())
            return existing;
        String base64 = str(legacy.get("avatar"));
        if (base64 == null || base64.isBlank())
            return null;
        try {
            byte[] png = Base64.getMimeDecoder().decode(base64);
            if (png.length == 0)
                return null;
            String key = AvatarCache.hash(png);
            Path dir = ctx.avatarCacheDir();
            Files.createDirectories(dir);
            Files.write(dir.resolve(key + ".png"), png);
            return key;
        } catch (IllegalArgumentException | IOException e) {
            LOGGER.warn("旧头像迁移失败，忽略（头像可重建）。", e);
            return null;
        }
    }

    // ── servers ──────────────────────────────────────────────────

    private static void ensureServer(JsonArray servers, Converted converted) {
        for (JsonElement e : servers) {
            if (e instanceof JsonObject so
                    && converted.serverType().equals(str(so.get("type")))
                    && converted.apiRoot().equals(str(so.get("apiRoot")))) {
                if (converted.displayName() != null && str(so.get("displayName")) == null)
                    so.addProperty("displayName", converted.displayName());
                if (converted.oauthSeed() != null && so.get("oauth") == null)
                    so.add("oauth", converted.oauthSeed());
                return;
            }
        }
        JsonObject so = new JsonObject();
        so.addProperty("type", converted.serverType());
        so.addProperty("apiRoot", converted.apiRoot());
        if (converted.displayName() != null)
            so.addProperty("displayName", converted.displayName());
        if (converted.oauthSeed() != null)
            so.add("oauth", converted.oauthSeed());
        servers.add(so);
    }

    private static void ensureServerKind(JsonArray servers, String type) {
        for (JsonElement e : servers) {
            if (e instanceof JsonObject so && type.equals(str(so.get("type"))))
                return;
        }
        JsonObject so = new JsonObject();
        so.addProperty("type", type);
        servers.add(so);
    }

    // ── 落盘与旧文件处理 ──────────────────────────────────────────

    /**
     * 原子写全局文件（tmp → move）。fsync 交给存储层：load() 触发时链跑完还会
     * save() 回写（带 fsync 与权限收紧）；P2.4 外部触发同理经 ConfigStore 落盘。
     */
    private void writeGlobal(JsonObject out, MigrationContext ctx) throws IOException {
        Path target = ctx.globalConfigFile();
        Path dir = target.getParent();
        if (dir != null)
            Files.createDirectories(dir);
        Path tmp = target.resolveSibling(target.getFileName() + ".migrate.tmp");
        Files.writeString(tmp, gson.toJson(out), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 删除本实例载荷（全局文件已持久化才会走到这里）；目录空了就删；实例配置改名 .migrated。 */
    private static void cleanupLegacy(MigrationContext ctx, String id) {
        Path home = ctx.legacyHomeDir();
        Path payload = home.resolve(id + ".json");
        try {
            Files.deleteIfExists(payload);
        } catch (IOException e) {
            LOGGER.warn("删除已迁移载荷失败：{}（下次启动会因去重跳过，不影响数据）。", payload, e);
        }
        try {
            if (Files.isDirectory(home)) {
                boolean hasJson;
                try (Stream<Path> files = Files.list(home)) {
                    hasJson = files.anyMatch(p -> p.getFileName().toString().endsWith(".json"));
                }
                if (!hasJson)
                    Files.delete(home);
            }
        } catch (IOException e) {
            LOGGER.debug("清理旧载荷目录失败（留空目录无害）。", e);
        }

        Path instanceConfig = ctx.legacyGameConfigDir().resolve("accounts.json");
        if (!Files.isRegularFile(instanceConfig))
            return;
        Path migrated = instanceConfig.resolveSibling("accounts.json.migrated");
        int suffix = 1;
        try {
            while (Files.exists(migrated)) {
                suffix++;
                migrated = instanceConfig.resolveSibling("accounts.json.migrated-" + suffix);
            }
            Files.move(instanceConfig, migrated);
            LOGGER.info("实例配置已改名保留：{} → {}", instanceConfig.getFileName(), migrated.getFileName());
        } catch (IOException e) {
            // 全局文件此时已落盘，改名失败不回滚；下次启动重跑 3→4 由去重兜底。
            LOGGER.warn("实例配置改名 .migrated 失败（数据已在全局文件，不影响）。", e);
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────

    private static String str(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    /** v4 统一存无横线小写 32 位 hex（定稿 v4.json 格式）；非 UUID 形状原样保留。 */
    private static String normalizeUuid(String raw) {
        if (raw == null)
            return null;
        String s = raw.trim().toLowerCase();
        String hex = s.replace("-", "");
        if (hex.length() == 32 && hex.chars().allMatch(c -> HEX.indexOf(c) >= 0))
            return hex;
        return raw.trim();
    }

    /** 去重键 (type, playerUuid, server)，server 对 microsoft / offline 取空串。 */
    private static String dedupKey(JsonObject v4Account) {
        String uuid = "";
        if (v4Account.get("profile") instanceof JsonObject p && str(p.get("uuid")) != null)
            uuid = str(p.get("uuid"));
        String server = str(v4Account.get("server"));
        String type = str(v4Account.get("type"));
        return (type == null ? "" : type) + '\u0000' + uuid + '\u0000' + (server == null ? "" : server);
    }
}