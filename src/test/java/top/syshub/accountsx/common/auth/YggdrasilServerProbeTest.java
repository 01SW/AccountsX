package top.syshub.accountsx.common.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.net.HttpGateway;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探针取 client_id 的顺序：内置 apiRoot 表 → YGGC openid 配置里的 shared_client_id → 视为不支持 OAuth。
 * 根 meta 不提供 client_id：YGGC 规范把它放在 openid 配置里。网关全假，无网络。
 */
class YggdrasilServerProbeTest {

    private static final String API_ROOT = "http://injector.test/api/yggdrasil";
    private static final String OPENID_URL = API_ROOT + "/.well-known/openid";

    /** 只服务 GET 的网关：按 url 查表返回，查不到即失败，用来暴露未预期的请求。 */
    private static final class StubGateway implements HttpGateway {
        final Map<String, JsonObject> responses = new HashMap<>();

        StubGateway put(String url, JsonObject body) {
            this.responses.put(url, body);
            return this;
        }

        @Override
        public JsonObject get(String url) throws IOException {
            JsonObject body = this.responses.get(url);
            if (body == null)
                throw new IOException("unexpected GET " + url);
            return body;
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
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] getBinary(String url) {
            throw new UnsupportedOperationException();
        }
    }

    /** openid 配置：默认带字符串形式的 shared_client_id，传 null 表示服务器不发布它。 */
    private static JsonObject openidConfig(JsonElement sharedClientId) {
        JsonObject config = YggdrasilFixtures.openidConfig(API_ROOT);
        config.remove("shared_client_id");
        if (sharedClientId != null)
            config.add("shared_client_id", sharedClientId);
        return config;
    }

    private static OAuthEndpoints probeOAuth(JsonObject root, JsonObject config, Map<String, String> known)
            throws IOException {
        StubGateway gateway = new StubGateway().put(API_ROOT, root);
        if (config != null)
            gateway.put(OPENID_URL, config);
        return YggdrasilServerProbe.probe(gateway, API_ROOT, known).oauth();
    }

    private static boolean supports(OAuthEndpoints oauth) {
        return YggdrasilDeviceCodeFlow.supportsDeviceCode(
                new AuthServer(API_ROOT, AuthServerKind.YGGDRASIL, "FakeInjector", oauth, 0L));
    }

    @Test
    void builtinTableWinsOverSharedClientId() throws IOException {
        OAuthEndpoints oauth = probeOAuth(YggdrasilFixtures.rootMeta(API_ROOT),
                openidConfig(new com.google.gson.JsonPrimitive("26")), Map.of(API_ROOT, "fake-known"));

        assertThat(oauth).isNotNull();
        assertThat(oauth.clientId()).isEqualTo("fake-known");
        assertThat(supports(oauth)).isTrue();
    }

    @Test
    void clientIdFallsBackToOpenidConfiguration() throws IOException {
        OAuthEndpoints oauth = probeOAuth(YggdrasilFixtures.rootMeta(API_ROOT),
                openidConfig(new com.google.gson.JsonPrimitive("26")), Map.of());

        assertThat(oauth).isNotNull();
        assertThat(oauth.clientId()).isEqualTo("26");
        assertThat(oauth.deviceAuthorizationEndpoint()).isEqualTo(API_ROOT + "/oauth/device");
        assertThat(supports(oauth)).isTrue();
    }

    @Test
    void sharedClientIdAcceptsJsonNumber() throws IOException {
        JsonObject config = YggdrasilFixtures.openidConfig(API_ROOT);
        config.addProperty("shared_client_id", 26);

        OAuthEndpoints oauth = probeOAuth(YggdrasilFixtures.rootMeta(API_ROOT), config, Map.of());

        assertThat(oauth).isNotNull();
        assertThat(oauth.clientId()).isEqualTo("26");
    }

    @Test
    void withoutAnyClientIdServerHasNoOAuth() throws IOException {
        OAuthEndpoints oauth = probeOAuth(YggdrasilFixtures.rootMeta(API_ROOT), openidConfig(null), Map.of());

        assertThat(oauth).isNull();
    }

    @Test
    void builtinTableIsKeyedByApiRootNotHost() throws IOException {
        // 旧实现按 host 匹配；现在只认 apiRoot，主机名作键不再命中。
        OAuthEndpoints oauth = probeOAuth(YggdrasilFixtures.rootMeta(API_ROOT), openidConfig(null),
                Map.of("injector.test", "fake-known"));

        assertThat(oauth).as("按主机名写的键不应命中").isNull();
    }

    @Test
    void apiRootKeyIsNormalized() {
        assertThat(OAuthClientIds.key("HTTPS://Skin.JSUMC.fun/api/yggdrasil/"))
                .isEqualTo("https://skin.jsumc.fun/api/yggdrasil");
        assertThat(OAuthClientIds.key("https://skin.jsumc.fun:443/api/yggdrasil"))
                .isEqualTo("https://skin.jsumc.fun:443/api/yggdrasil");
    }

    @Test
    void withoutOpenidConfigurationServerHasNoOAuth() throws IOException {
        JsonObject root = YggdrasilFixtures.rootMeta(API_ROOT);
        root.getAsJsonObject("meta").remove("feature.openid_configuration_url");

        OAuthEndpoints oauth = probeOAuth(root, null, Map.of(API_ROOT, "fake-known"));

        assertThat(oauth).isNull();
    }
}
