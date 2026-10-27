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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

/**
 * 3→4 {@code UNIFY_AND_DECOUPLE}（方案 C，见 REFACTORING/08-config-schema.md）：
 * 从"实例配置 {@code id} 索引 + {@code ~/.accountsx/<id>.json} 载荷"收敛为全局单文件。
 *
 * <p>一步完成：唯一数据来源是<b>当前实例</b> {@code id} 指向的载荷，整份转成 v4 后
 * 覆盖写全局文件——不并入已有内容、不去重、不搬未知字段。v4 从未发布，磁盘上不存在
 * "别的实例先迁好的 v4 文件"，所以没有"合并"这回事。
 * {@code ~/.accountsx/} 下其余 {@code *.json} 是其他实例的账号集，一律不动
 * （未更新模组的实例还要用）。</p>
 *
 * <p>顺序刻意安排为"先落盘全局文件，再删载荷、改名实例配置"：载荷删除发生在
 * 全局文件原子写成功之后，崩溃窗口内旧文件仍在，下次启动会重跑一次（覆盖写，结果相同）。</p>
 *
 * <p>迁移过程中还会：反推 servers（{@code oauth} 种子取自带登录信封的账号，与账号顺序无关）、拆
 * {@code "OAuth {\u2026}"} 登录信封为结构化 credentials（解析失败降级为"需重登"，
 * 保留账号条目）、把旧 base64 头像外移到 {@code ~/.cache/accountsx/avatars/}（D4，
 * 失败不阻塞——头像可重建）。</p>
 */
public final class UnifyAndDecouple implements Migration {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnifyAndDecouple.class);
    private static final String OAUTH_PREFIX = "OAuth ";
    private static final String HEX = "0123456789abcdef";
    private static final DateTimeFormatter BACKUP_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

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

        // 一步完成：把本实例的整份旧账号直接转成 v4，整体覆盖全局文件。
        // 不读取全局文件已有内容——v4 没发布过，不存在"别人已经迁好"的文档需要合并。
        JsonObject out = new JsonObject();
        JsonArray outAccounts = new JsonArray();
        JsonArray outServers = new JsonArray();
        for (JsonElement element : legacy) {
            if (!(element instanceof JsonObject account)) {
                LOGGER.warn("旧载荷含非对象条目，已忽略。");
                continue;
            }
            Converted converted = toV4(account, ctx);
            if (converted == null)
                continue;
            if (converted.serverType() != null)
                addServer(outServers, converted);
            outAccounts.add(converted.account());
        }
        LOGGER.info("已将 {} 个旧账号转换为 v4。", outAccounts.size());

        // mojang / offline 是 builtin kind，不写入 servers 列表。
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
            LOGGER.info("旧载荷文件不存在（实例从未添加过账号）：{}", payload);
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
            // 载荷读不懂就抛：宁可让存储层备份降级，也不删掉一个还没写进全局文件的载荷。
            throw new IllegalStateException("旧载荷解析失败: " + payload, e);
        }
    }

    // ── 单账号转换 ────────────────────────────────────────────────

    private Converted toV4(JsonObject legacy, MigrationContext ctx) {
        String legacyType = str(legacy.get("type"));
        String type = switch (legacyType == null ? "" : legacyType) {
            case "offline" -> "offline";
            case "microsoft" -> "microsoft";
            case "injector.authlib-injector" -> "yggdrasil";
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

        boolean injector = "yggdrasil".equals(type);
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
                    // token_endpoint / client_id 归服务器：先种进 servers 的 oauth 块，
                    // 首次探测（P3.1）再从 OIDC 元数据补全 device/userinfo 端点。
                    oauthSeed = new JsonObject();
                    oauthSeed.addProperty("clientId", str(envelope.get("client_id")));
                    oauthSeed.addProperty("tokenEndpoint", str(envelope.get("token_endpoint")));
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

    /**
     * 同一服务器的多个账号只留一个条目：建过就在已有条目上补缺，不再新建。
     *
     * <p>OAuth 信封只挂在部分账号上（密码登录的账号没有），所以条目第一次建出来时可能没有
     * {@code oauth}。后续带信封的账号必须能把它补进去——{@code servers[].oauth} 是整台服务器
     * 共用的，凭据在账号自己的 {@code credentials} 里；漏了种子，读回时这些账号会降级成
     * "需重登"。补缺与账号在载荷里的先后顺序无关，两种顺序结果一致。</p>
     */
    private static void addServer(JsonArray servers, Converted converted) {
        for (JsonElement e : servers) {
            if (e instanceof JsonObject so
                    && converted.serverType().equals(str(so.get("type")))
                    && converted.apiRoot().equals(str(so.get("apiRoot")))) {
                fillServer(so, converted);
                return;
            }
        }
        JsonObject so = new JsonObject();
        so.addProperty("type", converted.serverType());
        so.addProperty("apiRoot", converted.apiRoot());
        fillServer(so, converted);
        servers.add(so);
    }

    /**
     * 补齐条目里缺的字段，已有的不覆盖：同一 apiRoot 上第二个账号的 {@code accountName} 或
     * 第二份 OAuth 信封（client_id 不同的情况）都以先到者为准——servers 条目只有一份 oauth，
     * 这是数据模型当前的边界，不值得为冲突刷告警。
     */
    private static void fillServer(JsonObject server, Converted converted) {
        if (!server.has("displayName") && converted.displayName() != null)
            server.addProperty("displayName", converted.displayName());
        if (!server.has("oauth") && converted.oauthSeed() != null)
            server.add("oauth", converted.oauthSeed());
    }

    // ── 落盘与旧文件处理 ──────────────────────────────────────────

    /**
     * 原子写全局文件（tmp → move）。fsync 交给存储层：load() 触发时链跑完还会
     * save() 回写（带 fsync 与权限收紧）；P2.4 外部触发同理经 ConfigStore 落盘。
     */
    private void writeGlobal(JsonObject out, MigrationContext ctx) throws IOException {
        Path target = ctx.globalConfigFile();
        backupIfUnreadable(target);
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

    /**
     * 旧全局文件读不懂（手工改坏 / 写一半断电 / 同步冲突）时先整份备份再覆盖。
     *
     * <p>迁移是唯一的"盲写方"：{@code JsonConfigStore.load()} 的备份 + 只读降级要等触发器跑完
     * 才轮到，那时文件已被换成合法 v4，拦不住。备份失败则放弃覆盖——宁可这次启动报错，
     * 也不要把用户还能手工抢救的数据抹掉。</p>
     */
    private static void backupIfUnreadable(Path target) throws IOException {
        if (!Files.isRegularFile(target))
            return;
        String reason = null;
        try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed instanceof JsonObject)
                return;
            reason = "内容不是 JSON object";
        } catch (RuntimeException e) {
            reason = e.toString();
        }

        Path bak = target.resolveSibling(target.getFileName() + ".bak-" + LocalDateTime.now().format(BACKUP_TS));
        int suffix = 1;
        while (Files.exists(bak)) {
            bak = target.resolveSibling(target.getFileName() + ".bak-"
                    + LocalDateTime.now().format(BACKUP_TS) + "-" + suffix++);
        }
        try {
            Files.copy(target, bak);
        } catch (IOException e) {
            throw new IOException("旧全局配置无法解析且备份失败，拒绝覆盖：" + target, e);
        }
        LOGGER.error("旧全局配置无法解析（{}），已备份到 {}，随后写入迁移结果。", reason, bak);
    }

    /** 删除本实例载荷（全局文件已持久化才会走到这里）；目录空了就删；实例配置改名 .migrated。 */
    private static void cleanupLegacy(MigrationContext ctx, String id) {
        Path home = ctx.legacyHomeDir();
        Path payload = home.resolve(id + ".json");
        try {
            Files.deleteIfExists(payload);
        } catch (IOException e) {
            LOGGER.warn("删除已迁移载荷失败：{}（下次启动会重跑一次 3→4，覆盖写结果相同，不影响数据）。", payload, e);
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
        try {
            Files.move(instanceConfig, instanceConfig.resolveSibling("accounts.json.migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("实例配置已改名保留：accounts.json → accounts.json.migrated");
        } catch (IOException e) {
            // 全局文件此时已落盘，改名失败不回滚；下次启动会重跑一次（覆盖写，结果相同）。
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
}