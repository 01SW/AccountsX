package top.syshub.accountsx.common.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.storage.migration.Migration;
import top.syshub.accountsx.common.storage.migration.MigrationChain;
import top.syshub.accountsx.common.storage.migration.MigrationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2.2 存储层测试（05 文档 1.3b 的"读失败不覆写"是本类核心断言）。
 * 全程 jimfs，不碰真实磁盘；所有凭据值用 fake- 前缀。
 */
class JsonConfigStoreTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // unix()：POSIX 路径与权限视图，跨宿主平台行为一致（Windows 默认会拒绝 /config/x.json 这类路径）
    private final FileSystem fs = Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("basic", "posix").build());
    private final Path path = fs.getPath("/config/accountsx.json");
    private final List<String> toastTitles = new ArrayList<>();

    private JsonConfigStore store(MigrationChain chain) {
        MigrationContext ctx = new MigrationContext() {
            @Override
            public FileSystem fs() {
                return fs;
            }

            @Override
            public Path legacyGameConfigDir() {
                return fs.getPath("/game/config/accountsx");
            }

            @Override
            public Path legacyHomeDir() {
                return fs.getPath("/home/.accountsx");
            }
        };
        return new JsonConfigStore(fs, path, GSON, chain, ctx,
                (title, desc, args) -> toastTitles.add(title));
    }

    private static Function<String, String> envOf(Map<String, String> map) {
        return map::get;
    }

    // ── 路径解析（决策 D3） ──────────────────────────────────────────

    @Test
    void resolvePath_prefersAccountsxConfig() {
        Path p = JsonConfigStore.resolvePath(envOf(Map.of(
                "ACCOUNTSX_CONFIG", "/custom/acc.json",
                "XDG_CONFIG_HOME", "/xdg")), "/home/u", fs);
        assertThat(p).isEqualTo(fs.getPath("/custom/acc.json"));
    }

    @Test
    void resolvePath_fallsBackToXdgThenUserHome() {
        Path xdg = JsonConfigStore.resolvePath(envOf(Map.of("XDG_CONFIG_HOME", "/xdg")), "/home/u", fs);
        assertThat(xdg).isEqualTo(fs.getPath("/xdg/accountsx.json"));

        Path home = JsonConfigStore.resolvePath(envOf(Map.of()), "/home/u", fs);
        assertThat(home).isEqualTo(fs.getPath("/home/u/.config/accountsx.json"));
    }

    @Test
    void resolvePath_blankOverrideTreatedAsUnset() {
        Path p = JsonConfigStore.resolvePath(envOf(Map.of(
                "ACCOUNTSX_CONFIG", "",
                "XDG_CONFIG_HOME", "/xdg")), "/home/u", fs);
        assertThat(p).isEqualTo(fs.getPath("/xdg/accountsx.json"));
    }

    // ── 正常路径 ────────────────────────────────────────────────────

    @Test
    void load_missingFile_returnsEmptyCurrentVersion_notReadOnly() throws IOException {
        ConfigRoot root = store(MigrationChain.EMPTY).load();
        assertThat(root.version()).isEqualTo(ConfigStore.CURRENT_VERSION);
        assertThat(root.servers()).isEmpty();
        assertThat(root.accounts()).isEmpty();
        // 首次运行落盘空配置（08 文档）；旧实现的问题不是"写空配置"，而是"读失败后也写"
        assertThat(Files.exists(path)).isTrue();
        assertThat(store(MigrationChain.EMPTY).isReadOnly()).isFalse();
    }

    @Test
    void saveThenLoad_roundTripPreservesServersAccountsAndUnknownFields() throws IOException {
        JsonConfigStore store = store(MigrationChain.EMPTY);
        ConfigRoot root = ConfigRoot.empty(ConfigStore.CURRENT_VERSION);
        root.accounts().add(JsonParser.parseString(
                "{\"type\":\"offline\",\"profile\":{\"name\":\"Steve\"},\"avatarKey\":\"fake-key\"}"));
        root.servers().add(JsonParser.parseString(
                "{\"type\":\"yggdrasil\",\"apiRoot\":\"https://example.invalid/api/yggdrasil\",\"cachedAt\":1}"));
        root.raw().add("extra", JsonParser.parseString("{\"future\":true}")); // 未知字段必须原样回写
        store.save(root);

        ConfigRoot reloaded = store.load();
        assertThat(reloaded.version()).isEqualTo(ConfigStore.CURRENT_VERSION);
        assertThat(reloaded.accounts().get(0).getAsJsonObject().get("avatarKey").getAsString()).isEqualTo("fake-key");
        assertThat(reloaded.servers().get(0).getAsJsonObject().get("apiRoot").getAsString())
                .isEqualTo("https://example.invalid/api/yggdrasil");
        assertThat(reloaded.raw().getAsJsonObject("extra").get("future").getAsBoolean()).isTrue();
    }

    @Test
    void save_leavesNoTmpAndTightensPosixPermissions() throws IOException {
        store(MigrationChain.EMPTY).save(ConfigRoot.empty(ConfigStore.CURRENT_VERSION));
        assertThat(Files.exists(path.resolveSibling("accountsx.json.tmp"))).isFalse();
        assertThat(Files.getPosixFilePermissions(path))
                .containsExactlyInAnyOrderElementsOf(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(path.getParent()))
                .containsExactlyInAnyOrderElementsOf(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }

    // ── 降级路径：1.3b 防丢数据 ─────────────────────────────────────

    @Test
    void load_corruptFile_backsUpAndNeverOverwrites() throws IOException {
        Files.createDirectories(path.getParent());
        String corrupt = "{ this is not json ]";
        Files.writeString(path, corrupt, StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(path);

        JsonConfigStore store = store(MigrationChain.EMPTY);
        ConfigRoot root = store.load();

        assertThat(root.accounts()).isEmpty();
        assertThat(store.isReadOnly()).isTrue();
        assertThat(toastTitles).containsExactly("accountsx.config.degraded.title");

        // 备份存在且与原文件逐字节一致
        List<Path> backups = findBackups();
        assertThat(backups).hasSize(1);
        assertThat(Files.readAllBytes(backups.get(0))).isEqualTo(originalBytes);

        // 降级后 save 被丢弃：原文件逐字节不变（审计 1.8 的核心回归）
        store.save(ConfigRoot.empty(ConfigStore.CURRENT_VERSION));
        assertThat(Files.readAllBytes(path)).isEqualTo(originalBytes);
    }

    @Test
    void load_nonObjectRoot_degrades() throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "[]", StandardCharsets.UTF_8);
        assertThat(store(MigrationChain.EMPTY).load().accounts()).isEmpty();
        assertThat(toastTitles).containsExactly("accountsx.config.degraded.title");
    }

    @Test
    void load_missingVersion_degrades() throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"servers\":[]}", StandardCharsets.UTF_8);
        assertThat(store(MigrationChain.EMPTY).load().accounts()).isEmpty();
        assertThat(toastTitles).containsExactly("accountsx.config.degraded.title");
    }

    @Test
    void load_versionNewerThanCurrent_noBackupReadOnlyTooNewToast() throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"version\":99,\"servers\":[],\"accounts\":[]}", StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(path);

        JsonConfigStore store = store(MigrationChain.EMPTY);
        store.load();

        assertThat(store.isReadOnly()).isTrue();
        assertThat(toastTitles).containsExactly("accountsx.config.tooNew.title");
        assertThat(findBackups()).isEmpty(); // 文件未损坏，不需要备份
        store.save(ConfigRoot.empty(ConfigStore.CURRENT_VERSION));
        assertThat(Files.readAllBytes(path)).isEqualTo(originalBytes);
    }

    @Test
    void load_olderVersionWithoutChain_degradesWithBackup() throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"version\":3,\"id\":\"fake-id\"}", StandardCharsets.UTF_8);

        JsonConfigStore store = store(MigrationChain.EMPTY);
        store.load();

        assertThat(store.isReadOnly()).isTrue();
        assertThat(toastTitles).containsExactly("accountsx.config.degraded.title");
        assertThat(findBackups()).hasSize(1);
    }

    @Test
    void load_olderVersionWithChain_upgradesInPlace() throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"version\":3,\"accounts\":[{\"type\":\"offline\"}]}", StandardCharsets.UTF_8);

        Migration fakeV3toV4 = new Migration() {
            @Override
            public int fromVersion() {
                return 3;
            }

            @Override
            public int toVersion() {
                return 4;
            }

            @Override
            public JsonObject apply(JsonObject config, MigrationContext ctx) {
                config.add("servers", new com.google.gson.JsonArray());
                config.remove("accounts");
                config.addProperty("upgraded", true);
                return config;
            }
        };
        JsonConfigStore store = store(new MigrationChain(List.of(fakeV3toV4)));
        ConfigRoot root = store.load();

        assertThat(store.isReadOnly()).isFalse();
        assertThat(toastTitles).isEmpty();
        assertThat(root.version()).isEqualTo(ConfigStore.CURRENT_VERSION);
        assertThat(root.raw().get("upgraded").getAsBoolean()).isTrue();
    }

    // ── 安全约束 ────────────────────────────────────────────────────

    @Test
    void configRoot_toStringDoesNotLeakTokens() {
        ConfigRoot root = ConfigRoot.empty(ConfigStore.CURRENT_VERSION);
        root.accounts().add(JsonParser.parseString(
                "{\"credentials\":{\"accessToken\":\"fake-secret-token\"}}"));
        assertThat(root.toString()).doesNotContain("fake-secret-token");
    }

    private List<Path> findBackups() throws IOException {
        try (Stream<Path> s = Files.list(path.getParent())) {
            return s.filter(p -> p.getFileName().toString().startsWith("accountsx.json.bak-")).toList();
        }
    }
}
