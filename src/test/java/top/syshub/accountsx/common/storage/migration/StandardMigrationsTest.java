package top.syshub.accountsx.common.storage.migration;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.storage.ConfigStore;
import top.syshub.accountsx.common.utils.image.AvatarCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2.3 迁移链测试（08 文档测试清单：每跳一个用例 + v0→4 端到端 + 方案 C 场景矩阵）。
 * 全程 jimfs；所有凭据值 fake- 前缀。
 */
class StandardMigrationsTest {

    private static final Gson GSON = new Gson();
    private static final String ID = "11111111-2222-3333-4444-555555555555";

    private final FileSystem fs = Jimfs.newFileSystem(
            Configuration.unix().toBuilder().setAttributeViews("basic", "posix").build());
    private final Path global = fs.getPath("/config/accountsx.json");
    private final Path instanceDir = fs.getPath("/game/config/accountsx");
    private final Path legacyHome = fs.getPath("/home/.accountsx");
    private final Path avatarDir = fs.getPath("/home/.cache/accountsx/avatars");

    private final MigrationContext ctx = new MigrationContext() {
        @Override
        public FileSystem fs() {
            return fs;
        }

        @Override
        public Path legacyGameConfigDir() {
            return instanceDir;
        }

        @Override
        public Path legacyHomeDir() {
            return legacyHome;
        }

        @Override
        public Path globalConfigFile() {
            return global;
        }

        @Override
        public Path avatarCacheDir() {
            return avatarDir;
        }
    };

    private JsonObject doc(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private void writeInstanceConfig(String id) throws IOException {
        Files.createDirectories(instanceDir);
        Files.writeString(instanceDir.resolve("accounts.json"),
                "{\"version\":3,\"id\":\"" + id + "\"}", StandardCharsets.UTF_8);
    }

    private void writePayload(String id, String accountsJson) throws IOException {
        Files.createDirectories(legacyHome);
        Files.writeString(legacyHome.resolve(id + ".json"), accountsJson, StandardCharsets.UTF_8);
    }

    // ── 逐跳 ─────────────────────────────────────────────────────

    @Test
    void hop0to1_removesInjectorAccounts() {
        JsonObject config = doc("{\"version\":0,\"accounts\":[{\"type\":\"INJECTOR\"},{\"type\":\"OFFLINE\"}]}");
        JsonObject out = new InjectorSafety().apply(config, ctx);

        assertThat(out.getAsJsonArray("accounts")).hasSize(1);
        assertThat(out.getAsJsonArray("accounts").get(0).getAsJsonObject().get("type").getAsString()).isEqualTo("OFFLINE");
        assertThat(out.get("version").getAsInt()).isEqualTo(1);
    }

    @Test
    void hop1to2_renamesKnownAndDropsUnknown() {
        JsonObject config = doc("{\"version\":1,\"accounts\":[{\"type\":\"OFFLINE\"},{\"type\":\"MICROSOFT\"},"
                + "{\"type\":\"INJECTOR\"},{\"type\":\"BOGUS\"}]}");
        JsonObject out = new RenameAccountType().apply(config, ctx);

        JsonArray accounts = out.getAsJsonArray("accounts");
        assertThat(accounts).hasSize(3);
        assertThat(accounts.get(0).getAsJsonObject().get("type").getAsString()).isEqualTo("offline");
        assertThat(accounts.get(1).getAsJsonObject().get("type").getAsString()).isEqualTo("microsoft");
        assertThat(accounts.get(2).getAsJsonObject().get("type").getAsString()).isEqualTo("injector.authlib-injector");
        assertThat(out.get("version").getAsInt()).isEqualTo(2);
    }

    @Test
    void hop2to3_extractsPayloadUnderIdAndKeepsExistingId() throws IOException {
        JsonObject config = doc("{\"version\":2,\"id\":\"fixed-id\",\"accounts\":[{\"type\":\"offline\"}]}");
        JsonObject out = new SecurityStorage(GSON).apply(config, ctx);

        assertThat(out.get("id").getAsString()).isEqualTo("fixed-id");
        assertThat(out.has("accounts")).isFalse();
        assertThat(out.get("version").getAsInt()).isEqualTo(3);
        assertThat(Files.readString(legacyHome.resolve("fixed-id.json"), StandardCharsets.UTF_8))
                .contains("offline");
    }

    // ── 3→4 UNIFY_AND_DECOUPLE ───────────────────────────────────

    private JsonObject legacyV3Doc() {
        return doc("{\"version\":3,\"id\":\"" + ID + "\"}");
    }

    private String legacyPayload() {
        String b64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        return "["
                + "{\"type\":\"offline\",\"storage\":{\"playerName\":\"Steve\",\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf5\"}},"
                + "{\"type\":\"microsoft\",\"storage\":{\"accessToken\":\"fake-mc-token\",\"playerName\":\"Steve\",\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf5\"},"
                + "\"microsoftAccountAccessToken\":\"fake-xbl-token\",\"microsoftAccountRefreshToken\":\"fake-ms-refresh\"},"
                + "{\"type\":\"injector.authlib-injector\",\"storage\":{\"accessToken\":\"fake-ygg-token\",\"playerName\":\"Alex\",\"playerUUID\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"},"
                + "\"server\":\"https://mcskin.ecustvr.top/api/yggdrasil\",\"loginToken\":\"fake-ygg-token\",\"accountName\":\"ECUSTMC\",\"avatarKey\":\"fake-key-1\"},"
                + oauthAccountJson()
                + ","
                // 统一通行证类型已移除：迁移应告警并跳过该条目，其余账号照常转换
                + "{\"type\":\"injector.united\",\"storage\":{\"accessToken\":\"fake-united-token\",\"playerName\":\"Alex\",\"playerUUID\":\"cccccccc-dddd-eeee-ffff-aaaaaaaaaaaa\"},"
                + "\"server\":\"https://auth.mc-user.com:233/0123456789abcdef0123456789abcdef\",\"loginToken\":\"fake-united-token\",\"accountName\":\"UnitedPass\",\"avatar\":\"" + b64 + "\"}"
                + "]";
    }

    /** OAuth 信封含内嵌引号，用 Gson 现场拼，避免 fixture 里三层转义。 */
    private String oauthAccountJson() {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("token_endpoint", "https://littleskin.cn/oauth/token");
        envelope.addProperty("refresh_token", "fake-ls-refresh");
        envelope.addProperty("client_id", "1214");
        JsonObject account = new JsonObject();
        account.addProperty("type", "injector.authlib-injector");
        JsonObject storage = new JsonObject();
        storage.addProperty("accessToken", "fake-ls-at");
        storage.addProperty("playerName", "Alex");
        storage.addProperty("playerUUID", "bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
        account.add("storage", storage);
        account.addProperty("server", "https://littleskin.cn/api/yggdrasil");
        account.addProperty("loginToken", "OAuth " + envelope);
        account.addProperty("accountName", "LittleSkin");
        return account.toString();
    }

    @Test
    void hop3to4_convertsAllLegacyShapesAndCleansUp() throws Exception {
        writeInstanceConfig(ID);
        writePayload(ID, legacyPayload());

        JsonObject out = new UnifyAndDecouple(GSON).apply(legacyV3Doc(), ctx);

        assertThat(out.get("version").getAsInt()).isEqualTo(4);
        JsonArray accounts = out.getAsJsonArray("accounts");
        assertThat(accounts).hasSize(4);
        assertThat(accounts.toString()).doesNotContain("\"united\"");

        // offline：uuid 归一为无横线 32 位 hex，无 credentials
        JsonObject offline = accounts.get(0).getAsJsonObject();
        assertThat(offline.get("profile").getAsJsonObject().get("uuid").getAsString())
                .isEqualTo("069a79f444e94726a5befca90e38aaf5");
        assertThat(offline.has("credentials")).isFalse();

        // microsoft：XBL 中间 token 不进 v4（扩大明文落地面且可由 refreshToken 重建）
        JsonObject ms = accounts.get(1).getAsJsonObject();
        assertThat(ms.getAsJsonObject("credentials").get("accessToken").getAsString()).isEqualTo("fake-mc-token");
        assertThat(ms.getAsJsonObject("credentials").get("refreshToken").getAsString()).isEqualTo("fake-ms-refresh");
        assertThat(out.toString()).doesNotContain("fake-xbl-token");

        // yggdrasil 密码流：原样 accessToken + avatarKey 沿用
        JsonObject ygg = accounts.get(2).getAsJsonObject();
        assertThat(ygg.get("type").getAsString()).isEqualTo("yggdrasil");
        assertThat(ygg.get("server").getAsString()).isEqualTo("https://mcskin.ecustvr.top/api/yggdrasil");
        assertThat(ygg.getAsJsonObject("credentials").entrySet()).hasSize(1);
        assertThat(ygg.get("avatarKey").getAsString()).isEqualTo("fake-key-1");

        // yggdrasil OAuth：信封拆成 credentials{oauth,accessToken,refreshToken}，端点种进 server.oauth
        JsonObject oauth = accounts.get(3).getAsJsonObject();
        assertThat(oauth.getAsJsonObject("credentials").get("oauth").getAsBoolean()).isTrue();
        assertThat(oauth.getAsJsonObject("credentials").get("refreshToken").getAsString()).isEqualTo("fake-ls-refresh");
        assertThat(oauth.getAsJsonObject("credentials").get("accessToken").getAsString()).isEqualTo("fake-ls-at");
        assertThat(oauth.has("loginToken")).isFalse();

        // servers：2 个真实 yggdrasil，无 builtin mojang/offline、无 united
        assertThat(out.getAsJsonArray("servers")).hasSize(2);
        JsonObject lsServer = findServer(out, "https://littleskin.cn/api/yggdrasil");
        assertThat(lsServer.get("displayName").getAsString()).isEqualTo("LittleSkin");
        assertThat(lsServer.getAsJsonObject("oauth").get("clientId").getAsString()).isEqualTo("1214");
        assertThat(lsServer.getAsJsonObject("oauth").get("tokenEndpoint").getAsString())
                .isEqualTo("https://littleskin.cn/oauth/token");

        // 全局文件已落盘（先落盘再删旧文件）
        JsonObject onDisk = JsonParser.parseString(Files.readString(global, StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(onDisk.get("version").getAsInt()).isEqualTo(4);
        assertThat(onDisk.getAsJsonArray("accounts")).hasSize(4);

        // 旧文件处理（方案 C）：载荷被删、目录空了被删、实例配置改名 .migrated
        assertThat(Files.exists(legacyHome.resolve(ID + ".json"))).isFalse();
        assertThat(Files.exists(legacyHome)).isFalse();
        assertThat(Files.exists(instanceDir.resolve("accounts.json"))).isFalse();
        assertThat(Files.exists(instanceDir.resolve("accounts.json.migrated"))).isTrue();
    }

    @Test
    void hop3to4_brokenOAuthKeepsAccountAndClearsCredentials() throws Exception {
        writeInstanceConfig(ID);
        JsonObject bad = doc("{\"type\":\"injector.authlib-injector\","
                + "\"storage\":{\"accessToken\":\"fake-a\",\"playerName\":\"Alex\",\"playerUUID\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"},"
                + "\"server\":\"https://example.invalid/api/yggdrasil\"}");
        bad.addProperty("loginToken", "OAuth { not json");
        JsonObject missingField = doc("{\"type\":\"injector.authlib-injector\","
                + "\"storage\":{\"accessToken\":\"fake-b\",\"playerName\":\"Bob\",\"playerUUID\":\"bbbbbbbb-cccc-dddd-eeee-ffffffffffff\"},"
                + "\"server\":\"https://other.invalid/api/yggdrasil\"}");
        JsonObject partial = new JsonObject();
        partial.addProperty("token_endpoint", "https://other.invalid/oauth/token");
        missingField.addProperty("loginToken", "OAuth " + partial);
        writePayload(ID, "[" + bad + "," + missingField + "]");

        JsonObject out = new UnifyAndDecouple(GSON).apply(legacyV3Doc(), ctx);

        JsonArray accounts = out.getAsJsonArray("accounts");
        assertThat(accounts).hasSize(2);
        // 降级为"需要重新登录"：条目与 profile 保留、credentials 清空，账号不消失
        assertThat(accounts.get(0).getAsJsonObject().has("credentials")).isFalse();
        assertThat(accounts.get(0).getAsJsonObject().getAsJsonObject("profile").get("name").getAsString()).isEqualTo("Alex");
        assertThat(accounts.get(1).getAsJsonObject().has("credentials")).isFalse();
    }

    @Test
    void hop3to4_schemeC_leavesOtherInstancesPayloadsUntouched() throws Exception {
        writeInstanceConfig(ID);
        writePayload(ID, "[{\"type\":\"offline\",\"storage\":{\"playerName\":\"Steve\",\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf5\"}}]");
        byte[] other1 = "[{\"type\":\"offline\"}]".getBytes(StandardCharsets.UTF_8);
        Files.write(legacyHome.resolve("other-1.json"), other1);
        Files.write(legacyHome.resolve("other-2.json"), other1);

        new UnifyAndDecouple(GSON).apply(legacyV3Doc(), ctx);

        assertThat(Files.exists(legacyHome.resolve(ID + ".json"))).isFalse();
        assertThat(Files.readAllBytes(legacyHome.resolve("other-1.json"))).isEqualTo(other1);
        assertThat(Files.readAllBytes(legacyHome.resolve("other-2.json"))).isEqualTo(other1);
        assertThat(Files.exists(legacyHome)).isTrue(); // 还有别人的账号集，目录不删
    }

    @Test
    void hop3to4_overwritesExistingGlobalInsteadOfMerging() throws Exception {
        // v4 没发布过，全局文件里不可能有"要并入"的内容：整份覆盖，旧账号与未知字段都不保留。
        Files.createDirectories(global.getParent());
        Files.writeString(global, "{\"version\":4,\"extra\":{\"future\":true},\"servers\":[{\"type\":\"mojang\"}],"
                + "\"accounts\":[{\"type\":\"offline\",\"profile\":{\"name\":\"Stale\",\"uuid\":\"069a79f444e94726a5befca90e38aaf5\"}}]}",
                StandardCharsets.UTF_8);
        Files.createDirectories(instanceDir);
        Files.writeString(instanceDir.resolve("accounts.json.migrated"), "{\"version\":3}", StandardCharsets.UTF_8);
        writeInstanceConfig(ID);
        writePayload(ID, "["
                + "{\"type\":\"offline\",\"storage\":{\"playerName\":\"Bob\",\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf6\"}},"
                + "{\"type\":\"injector.authlib-injector\",\"storage\":{\"accessToken\":\"fake-t\",\"playerName\":\"Alex\",\"playerUUID\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"},"
                + "\"server\":\"https://example.invalid/api/yggdrasil\",\"loginToken\":\"fake-t\"}]");

        JsonObject out = new UnifyAndDecouple(GSON).apply(legacyV3Doc(), ctx);

        assertThat(out.getAsJsonArray("accounts")).hasSize(2);
        assertThat(out.toString()).doesNotContain("Stale").doesNotContain("extra");
        assertThat(out.getAsJsonArray("servers")).hasSize(1);
        assertThat(findServer(out, "https://example.invalid/api/yggdrasil").get("type").getAsString()).isEqualTo("yggdrasil");
        // 旧的 .migrated 被直接覆盖，不产生 .migrated-2
        assertThat(Files.readString(instanceDir.resolve("accounts.json.migrated"), StandardCharsets.UTF_8))
                .contains("\"id\"");
        assertThat(Files.exists(instanceDir.resolve("accounts.json.migrated-2"))).isFalse();
        // 覆盖真的落盘（不是只在内存里）
        JsonObject onDisk = JsonParser.parseString(Files.readString(global, StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(onDisk.get("version").getAsInt()).isEqualTo(4);
        assertThat(onDisk.getAsJsonArray("accounts")).hasSize(2);
        assertThat(onDisk.toString()).doesNotContain("Stale");
        // 读得懂的文件直接覆盖，不留备份
        try (var files = Files.list(global.getParent())) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .noneMatch(n -> n.startsWith("accountsx.json.bak-"));
        }
    }

    @Test
    void hop3to4_backsUpUnreadableGlobalBeforeOverwrite() throws Exception {
        // 坏掉的全局文件不能静默消失：先整份备份 + 记 error，再写入迁移结果。
        Files.createDirectories(global.getParent());
        byte[] broken = "{ this is not json ]".getBytes(StandardCharsets.UTF_8);
        Files.write(global, broken);
        writeInstanceConfig(ID);
        writePayload(ID, "[{\"type\":\"offline\",\"storage\":{\"playerName\":\"Steve\","
                + "\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf5\"}}]");

        new UnifyAndDecouple(GSON).apply(legacyV3Doc(), ctx);

        try (var files = Files.list(global.getParent())) {
            Path bak = files.filter(p -> p.getFileName().toString().startsWith("accountsx.json.bak-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("坏文件没有被备份"));
            assertThat(Files.readAllBytes(bak)).isEqualTo(broken);
        }
        JsonObject onDisk = JsonParser.parseString(Files.readString(global, StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(onDisk.get("version").getAsInt()).isEqualTo(4);
        assertThat(onDisk.getAsJsonArray("accounts")).hasSize(1);
    }

    @Test
    void hop3to4_corruptPayloadRefusesAndKeepsEverything() throws Exception {
        writeInstanceConfig(ID);
        writePayload(ID, "{ \"not\": \"an array\" }");
        assertThatThrownBy(() -> new UnifyAndDecouple(GSON).apply(legacyV3Doc(), ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数组");
        // 读不懂的载荷绝不删除
        assertThat(Files.exists(legacyHome.resolve(ID + ".json"))).isTrue();
        assertThat(Files.exists(instanceDir.resolve("accounts.json"))).isTrue();

        writePayload(ID, "{ this is not json ]");
        assertThatThrownBy(() -> new UnifyAndDecouple(GSON).apply(legacyV3Doc(), ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解析失败");
        assertThat(Files.exists(legacyHome.resolve(ID + ".json"))).isTrue();
        assertThat(Files.exists(global)).isFalse(); // 未落盘，全局文件原样（此前不存在）
    }

    @Test
    void hop3to4_missingIdRefusesToRun() {
        JsonObject config = doc("{\"version\":3}");
        assertThatThrownBy(() -> new UnifyAndDecouple(GSON).apply(config, ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id");
    }

    @Test
    void hop3to4_payloadMissingWritesEmptyGlobalAndCleansUp() throws Exception {
        writeInstanceConfig(ID); // 载荷不存在：实例从未添加过账号

        JsonObject out = new UnifyAndDecouple(GSON).apply(legacyV3Doc(), ctx);

        assertThat(out.getAsJsonArray("accounts")).isEmpty();
        assertThat(out.getAsJsonArray("servers")).hasSize(0); // 无 builtin mojang/offline
        assertThat(Files.exists(instanceDir.resolve("accounts.json.migrated"))).isTrue();
    }

    // ── 端到端 ───────────────────────────────────────────────────

    @Test
    void fullChain_v0ToV4_endToEnd() throws Exception {
        JsonObject v0 = doc("{\"version\":0,\"accounts\":[{\"type\":\"INJECTOR\"},"
                + "{\"type\":\"OFFLINE\",\"storage\":{\"playerName\":\"Steve\",\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf5\"}},"
                + "{\"type\":\"MICROSOFT\",\"storage\":{\"accessToken\":\"fake-mc\",\"playerName\":\"Sam\",\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf7\"},"
                + "\"microsoftAccountRefreshToken\":\"fake-r\"},"
                + "{\"type\":\"BOGUS\"}]}");

        JsonObject out = StandardMigrations.chain(GSON).upgrade(v0, 0, ctx);

        assertThat(out.get("version").getAsInt()).isEqualTo(ConfigStore.CURRENT_VERSION);
        // INJECTOR 在 0→1 删除、BOGUS 在 1→2 跳过；2→3 搬载荷、3→4 覆盖写全局并清理
        assertThat(out.getAsJsonArray("accounts")).hasSize(2);
        assertThat(out.getAsJsonArray("servers")).hasSize(0);
        assertThat(Files.exists(global)).isTrue();
        assertThat(Files.exists(legacyHome)).isFalse();
    }

    // ── 工具 ─────────────────────────────────────────────────────

    private static JsonObject findServer(JsonObject out, String apiRoot) {
        for (var e : out.getAsJsonArray("servers")) {
            JsonObject so = e.getAsJsonObject();
            if (so.has("apiRoot") && so.get("apiRoot").getAsString().equals(apiRoot))
                return so;
        }
        throw new AssertionError("server not found: " + apiRoot);
    }
}