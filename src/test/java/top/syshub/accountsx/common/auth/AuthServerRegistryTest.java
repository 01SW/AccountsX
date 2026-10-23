package top.syshub.accountsx.common.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.net.HttpGateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P3.1 注册表：探测去重（同服务器多账号只探测一次）、元数据内存缓存、
 * stale 降级、servers[] 往返（迁移形状不动）。网关全假，无网络。
 */
class AuthServerRegistryTest {

    private static final String LOCATOR = "http://injector.test";
    private static final String API_ROOT = "http://injector.test/api/yggdrasil";

    private final class FakeGateway implements HttpGateway {
        final Map<String, Integer> counts = new java.util.HashMap<>();
        boolean failGet = false;

        private void hit(String url) {
            counts.merge(url, 1, Integer::sum);
        }

        int count(String url) {
            return counts.getOrDefault(url, 0);
        }

        @Override
        public JsonObject get(String url) throws IOException {
            hit(url);
            if (failGet)
                throw new IOException("fake gateway down");
            if (url.equals(API_ROOT + "/.well-known/openid"))
                return YggdrasilFixtures.openidConfig(API_ROOT);
            if (url.equals(API_ROOT)) {
                try {
                    return YggdrasilFixtures.rootMeta(API_ROOT);
                } catch (IOException e) {
                    throw new IOException(e);
                }
            }
            throw new IOException("unexpected GET " + url);
        }

        @Override
        public JsonObject get(String url, Map<String, String> headers) throws IOException {
            return get(url);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, List<String>> head(String url) {
            hit("HEAD " + url);
            if (url.equals(LOCATOR))
                return Map.of("X-Authlib-Injector-API-Location", List.of("/api/yggdrasil"));
            return Map.of();
        }

        @Override
        public byte[] getBinary(String url) {
            // 头像可重建：返回坏响应，AvatarService 吞掉即可
            return new byte[0];
        }
    }

    private FakeGateway gateway;
    private AuthServerRegistry registry;
    private final List<Integer> changeEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        registry = new AuthServerRegistry(gateway, () -> changeEvents.add(1));
    }

    @Test
    void ensure_probesOnceAndDedupsByLocator() throws Exception {
        AuthServer first = registry.ensure(AuthServerKind.YGGDRASIL, LOCATOR, Map.of());
        assertThat(first.apiRoot()).isEqualTo(API_ROOT);
        assertThat(first.displayName()).isEqualTo("FakeInjector");
        assertThat(first.oauth().clientId()).isEqualTo("fake-shared-client");
        assertThat(first.oauth().deviceAuthorizationEndpoint()).isEqualTo(API_ROOT + "/oauth/device");
        assertThat(gateway.count(API_ROOT)).isEqualTo(1);
        assertThat(changeEvents).hasSize(1);

        // 同 locator 再登录：locator 短路 → 0 次 HEAD/GET
        AuthServer second = registry.ensure(AuthServerKind.YGGDRASIL, LOCATOR, Map.of());
        assertThat(second.apiRoot()).isEqualTo(API_ROOT);
        assertThat(gateway.count(API_ROOT)).isEqualTo(1);
        assertThat(gateway.count("HEAD " + LOCATOR)).isEqualTo(1);
        assertThat(changeEvents).hasSize(1);
        // ensure 只新增自己一条：mojang / offline 在 load() 时补齐，fresh 注册表没有
        assertThat(registry.servers()).hasSize(1);
    }

    @Test
    void metadata_isMemoryCachedAfterProbe() throws Exception {
        registry.ensure(AuthServerKind.YGGDRASIL, LOCATOR, Map.of());
        int before = gateway.count(API_ROOT);
        AuthServerMetadata m = registry.metadata(API_ROOT);
        assertThat(gateway.count(API_ROOT)).isEqualTo(before);
        assertThat(m.skinDomains()).containsExactly("skin.example.invalid");
        assertThat(m.profilePropertyKeys()).hasSize(1);
        assertThat(m.playerCertificateKeys()).hasSize(1);
    }

    @Test
    void metadata_probeOnMiss() throws Exception {
        // 未预热（重启后 refreshAll 未跑完的窗口）：兜底补测一次并缓存
        registry.metadata(API_ROOT);
        assertThat(gateway.count(API_ROOT)).isEqualTo(1);
        registry.metadata(API_ROOT);
        assertThat(gateway.count(API_ROOT)).isEqualTo(1);
    }

    @Test
    void refreshAll_keepsStaleOnFailure() throws Exception {
        registry.ensure(AuthServerKind.YGGDRASIL, LOCATOR, Map.of());
        long cachedAt = registry.byApiRoot(API_ROOT).cachedAt();
        changeEvents.clear();
        gateway.failGet = true;
        registry.refreshAll(Map.of());
        AuthServer still = registry.byApiRoot(API_ROOT);
        assertThat(still.displayName()).isEqualTo("FakeInjector");
        assertThat(still.cachedAt()).isEqualTo(cachedAt);
        assertThat(changeEvents).isEmpty();
    }

    @Test
    void resolveApiRoot_unitedConcat() throws Exception {
        assertThat(registry.resolveApiRoot(AuthServerKind.UNITED, "0123456789abcdef0123456789abcdef"))
                .isEqualTo("https://auth.mc-user.com:233/0123456789abcdef0123456789abcdef");
    }

    @Test
    void loadPersist_roundTripKeepsMigrationShape() throws Exception {
        JsonArray stored = new JsonArray();
        // 3→4 种下的形状：oauth 只有 clientId/tokenEndpoint，无 apiRoot 的固定条目
        stored.add(JsonParser.parseString("{\"type\":\"yggdrasil\",\"apiRoot\":\"" + API_ROOT
                + "\",\"futureField\":\"keep-me\",\"oauth\":{\"clientId\":\"fake-1214\",\"tokenEndpoint\":\"http://injector.test/oauth/token\"}}"));
        stored.add(JsonParser.parseString("{\"type\":\"mojang\"}"));
        registry.load(stored);

        assertThat(registry.servers()).hasSize(3); // + offline 补齐
        assertThat(registry.byApiRoot(API_ROOT).oauth().clientId()).isEqualTo("fake-1214");
        assertThat(registry.byApiRoot(API_ROOT).oauth().deviceAuthorizationEndpoint()).isNull();

        // 生产路径是 persist 回同一份 live 数组（snapshot 传 root.servers()），未知字段靠此对齐
        registry.persist(stored);
        JsonArray out = stored;
        JsonObject ygg = out.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(j -> "yggdrasil".equals(j.get("type").getAsString()))
                .findFirst().orElseThrow();
        assertThat(ygg.get("oauth").getAsJsonObject().get("tokenEndpoint").getAsString())
                .isEqualTo("http://injector.test/oauth/token");
        JsonObject mojang = out.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(j -> "mojang".equals(j.get("type").getAsString()))
                .findFirst().orElseThrow();
        assertThat(mojang.has("apiRoot")).isFalse();
        // 未建模字段原样回写：新→旧方向的字段不能被注册表吞掉
        assertThat(ygg.get("futureField").getAsString()).isEqualTo("keep-me");
    }

    @Test
    void oauth_mergeKeepsSeededFields() {
        OAuthEndpoints seeded = new OAuthEndpoints("fake-1214", "https://x/oauth/token", null, null);
        OAuthEndpoints probed = new OAuthEndpoints(null, null, "https://x/oauth/device", "https://x/oauth/userinfo");
        OAuthEndpoints merged = OAuthEndpoints.merge(seeded, probed);
        assertThat(merged.clientId()).isEqualTo("fake-1214");
        assertThat(merged.tokenEndpoint()).isEqualTo("https://x/oauth/token");
        assertThat(merged.deviceAuthorizationEndpoint()).isEqualTo("https://x/oauth/device");
    }

    @Test
    void ensure_builtinNoNetwork() throws Exception {
        AuthServer mojang = registry.ensure(AuthServerKind.MOJANG, "mojang", Map.of());
        assertThat(mojang.apiRoot()).isNull();
        assertThat(gateway.counts).isEmpty();
    }

    @Test
    void probeFailure_propagatesOnAdd() {
        gateway.failGet = true;
        assertThatThrownBy(() -> registry.ensure(AuthServerKind.YGGDRASIL, LOCATOR, Map.of()))
                .isInstanceOf(IOException.class);
        // 加服失败不落盘：条目不存在
        assertThat(registry.byApiRoot(API_ROOT)).isNull();
    }
}
