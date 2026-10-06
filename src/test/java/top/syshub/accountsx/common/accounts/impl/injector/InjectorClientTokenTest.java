package top.syshub.accountsx.common.accounts.impl.injector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider.AuthlibInjectorAccount;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.ui.Memory;
import top.syshub.accountsx.common.utils.NetworkUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外置登录（authlib-injector / drasl）的 {@code clientToken} 契约回归测试。
 *
 * <p>背景：drasl 的 {@code /authserver/refresh} 用 {@code mo.Some(req.ClientToken)} 强制比对
 * 客户端令牌（drasl {@code auth.go:304} → {@code model.go:383}），缺失即 403
 * {@code ForbiddenOperationException: Invalid token}。而 {@code /authserver/authenticate} 在请求省略
 * {@code clientToken} 时由服务端随机生成一个（{@code auth.go:178-192}），永远不会替客户端稳定下来。
 * 因此客户端必须自己生成一次、随账号持久化，并在登录与刷新时原样回传 —— 否则账号只在「登录那次」
 * 可用，下次启动刷新即失效。</p>
 *
 * <p>本测试用可注入的假网关断言请求体契约，并用 Gson 往返验证 {@code clientToken} 确实进了持久化载荷。</p>
 */
class InjectorClientTokenTest {

    private static final String SERVER = "https://drasl.example.com";
    private static final String YGGDRASIL = SERVER + "/api/yggdrasil";
    private static final String USER_NAME = "fake-user";
    private static final String PASSWORD = "fake-password";
    private static final String PLAYER_NAME = "FakePlayer";
    private static final UUID PLAYER_UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String ACCESS_TOKEN = "fake-access-token";
    private static final String NEW_ACCESS_TOKEN = "fake-access-token-2";

    /** 记录每次请求、按 URL 返回预置响应的假网关。 */
    private static final class ScriptedHttpGateway implements HttpGateway {
        private record Call(String method, String url, JsonElement body) {}

        private final List<Call> calls = new ArrayList<>();

        /** 非 null 时，refresh 端点返回该错误响应（模拟 drasl 的 403 Invalid token）。 */
        private JsonObject refreshError;

        List<Call> callsTo(String url) {
            return calls.stream().filter(c -> c.url().equals(url)).toList();
        }

        JsonObject lastBodyTo(String url) {
            List<Call> matched = callsTo(url);
            assertThat(matched).as("no request recorded for %s", url).isNotEmpty();
            return matched.get(matched.size() - 1).body().getAsJsonObject();
        }

        /** drasl 的 meta：只有 serverName，没有 openid_configuration_url（drasl 不支持 OAuth 设备码）。 */
        private static JsonObject yggdrasilMeta() {
            JsonObject meta = new JsonObject();
            meta.addProperty("serverName", "Drasl");
            JsonObject json = new JsonObject();
            json.add("meta", meta);
            return json;
        }

        private static JsonObject emptyProfile() {
            JsonObject json = new JsonObject();
            json.addProperty("id", PLAYER_UUID.toString().replace("-", ""));
            json.addProperty("name", PLAYER_NAME);
            // 无 textures 属性 → AvatarService 解析不到皮肤 URL，直接返回 null，不触发 AWT / 下载。
            json.add("properties", new JsonArray());
            return json;
        }

        private static JsonObject authenticateResponse() {
            JsonObject profile = new JsonObject();
            profile.addProperty("name", PLAYER_NAME);
            profile.addProperty("id", PLAYER_UUID.toString());

            JsonObject json = new JsonObject();
            json.addProperty("accessToken", ACCESS_TOKEN);
            json.add("selectedProfile", profile);
            return json;
        }

        private static JsonObject refreshResponse() {
            JsonObject profile = new JsonObject();
            profile.addProperty("name", PLAYER_NAME);
            profile.addProperty("id", PLAYER_UUID.toString());

            JsonObject json = new JsonObject();
            json.addProperty("accessToken", NEW_ACCESS_TOKEN);
            json.add("selectedProfile", profile);
            return json;
        }

        @Override
        public JsonObject get(String url) {
            calls.add(new Call("GET", url, null));
            if (url.equals(YGGDRASIL)) {
                return yggdrasilMeta();
            }
            if (url.startsWith(YGGDRASIL + "/sessionserver/session/minecraft/profile/")) {
                return emptyProfile();
            }
            throw new UnsupportedOperationException("fake: unexpected GET " + url);
        }

        @Override
        public JsonObject get(String url, Map<String, String> headers) {
            return get(url);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body) {
            return postJson(url, body, false);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) {
            calls.add(new Call("POST", url, body));
            if (url.equals(YGGDRASIL + "/authserver/authenticate")) {
                return authenticateResponse();
            }
            if (url.equals(YGGDRASIL + "/authserver/refresh")) {
                return refreshError != null ? refreshError : refreshResponse();
            }
            throw new UnsupportedOperationException("fake: unexpected POST " + url);
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData) {
            throw new UnsupportedOperationException("fake: postForm not expected (drasl 无 OAuth 端点)");
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) {
            return postForm(url, formData);
        }

        /** HEAD 用于解析 X-Authlib-Injector-API-Location；空头即让 provider 退回 {@code <base>/api/yggdrasil}。 */
        @Override
        public Map<String, List<String>> head(String url) {
            calls.add(new Call("HEAD", url, null));
            return Map.of();
        }

        @Override
        public byte[] getBinary(String url) {
            throw new UnsupportedOperationException("fake: getBinary not expected");
        }
    }

    private static AuthlibInjectorAccountProvider provider(HttpGateway http) {
        return new AuthlibInjectorAccountProvider(http);
    }

    /** 测试内自带的 {@link Memory} 实现：登录路径只用到 set/get，不需要屏幕状态。 */
    private static final class MapMemory implements Memory {
        private final Map<String, Object> values = new ConcurrentHashMap<>();

        @Override
        public <T> void set(String guid, T value) {
            values.put(guid, value);
        }

        @Override
        public <T> T get(String guid, Class<T> type) {
            return type.cast(values.get(guid));
        }

        @Override
        public boolean isScreenClosed() {
            return false;
        }
    }

    private static Memory loginMemory() {
        Memory memory = new MapMemory();
        memory.set("guid:as.login.injector.widgets.server_url", SERVER);
        memory.set("guid:as.login.injector.widgets.user_name", USER_NAME);
        memory.set("guid:as.login.injector.widgets.user_password", PASSWORD);
        memory.set("guid:as.login.injector.widgets.player_name", "");
        return memory;
    }

    /** 密码登录必须自带 clientToken，否则服务端每次都会新建 client，刷新时无 token 可比对。 */
    @Test
    void passwordLogin_sendsOwnClientToken() throws IOException {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();

        AuthlibInjectorAccount account = provider(fake).login(loginMemory());

        JsonObject body = fake.lastBodyTo(YGGDRASIL + "/authserver/authenticate");
        assertThat(body.get("clientToken").getAsString()).isNotBlank();
        assertThat(account.getClientToken()).isEqualTo(body.get("clientToken").getAsString());
    }

    /** clientToken 必须落到持久化载荷里，否则重启后刷新又变回无 token。 */
    @Test
    void clientToken_survivesPersistence() throws IOException {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();
        AuthlibInjectorAccount account = provider(fake).login(loginMemory());

        String json = NetworkUtils.GSON.toJson(account);
        assertThat(json).contains(account.getClientToken());

        AuthlibInjectorAccount restored = NetworkUtils.GSON.fromJson(json, AuthlibInjectorAccount.class);
        assertThat(restored.getClientToken()).isEqualTo(account.getClientToken());
        assertThat(restored.getLoginToken()).isEqualTo(account.getLoginToken());
    }

    /** 刷新必须回传持久化的 clientToken —— 这是 drasl 上「第二次打不开」的直接原因。 */
    @Test
    void refresh_sendsPersistedClientToken() throws ExecutionException, InterruptedException {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();
        String clientToken = UUID.randomUUID().toString();

        AuthlibInjectorAccount account = new AuthlibInjectorAccount(
                ACCESS_TOKEN, PLAYER_NAME, PLAYER_UUID,
                YGGDRASIL, PLAYER_UUID.toString(), "Drasl", null, 0L, clientToken
        );

        // refresh 内的 setProfile/setLoginProfile 要求 worker 线程，故经调度器在 worker 线程上执行。
        TaskScheduler.submitParallel(() -> provider(fake).refresh(account)).get();

        JsonObject body = fake.lastBodyTo(YGGDRASIL + "/authserver/refresh");
        assertThat(body.get("clientToken").getAsString()).isEqualTo(clientToken);
        assertThat(body.get("accessToken").getAsString()).isEqualTo(ACCESS_TOKEN);
        assertThat(account.getLoginToken()).isEqualTo(NEW_ACCESS_TOKEN);
    }

    /** 固定 32 字符十六进制：drasl 自己生成的就是这个形状，且第三方 Yggdrasil 普遍按 32 字符校验。 */
    @Test
    void generatedClientToken_is32HexChars() throws IOException {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();

        AuthlibInjectorAccount account = provider(fake).login(loginMemory());

        assertThat(account.getClientToken()).matches("[0-9a-f]{32}");
    }

    /** 兼容性：旧配置的账号没有 clientToken 字段，反序列化后为 null，不能因此加载失败。 */
    @Test
    void legacyPayloadWithoutClientToken_loads() {
        AuthlibInjectorAccount restored = NetworkUtils.GSON.fromJson(legacyAccountJson(), AuthlibInjectorAccount.class);

        assertThat(restored).isNotNull();
        assertThat(restored.getClientToken()).isNull();
        assertThat(restored.getLoginToken()).isEqualTo(ACCESS_TOKEN);
    }

    /** 记录用：断言假网关真的收到了 HEAD（transformServerBaseURL 的探测），避免测试假象。 */
    @Test
    void serverBaseUrl_isResolvedThroughHeadProbe() throws IOException {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();

        provider(fake).login(loginMemory());

        assertThat(fake.callsTo(SERVER)).isNotEmpty();
    }

    /** 旧账号刷新：没有 clientToken 时给出可操作提示，且不发出注定被 403 的请求。 */
    @Test
    void legacyAccountRefresh_failsActionablyWithoutRequest() {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();
        AuthlibInjectorAccount legacy = NetworkUtils.GSON.fromJson(legacyAccountJson(), AuthlibInjectorAccount.class);

        assertThatThrownBy(() -> TaskScheduler.submitParallel(() -> provider(fake).refresh(legacy)).get())
                .hasRootCauseInstanceOf(InjectorAuthException.class)
                .rootCause()
                .hasMessageContaining("clientToken");

        assertThat(fake.callsTo(YGGDRASIL + "/authserver/refresh")).isEmpty();
    }

    /** 服务端 403：携带 error/errorMessage 作为可操作提示，且绝不泄漏 accessToken。 */
    @Test
    void rejectedRefresh_doesNotLeakAccessToken() {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();
        JsonObject error = new JsonObject();
        error.addProperty("error", "ForbiddenOperationException");
        error.addProperty("errorMessage", "Invalid token");
        fake.refreshError = error;

        AuthlibInjectorAccount account = new AuthlibInjectorAccount(
                ACCESS_TOKEN, PLAYER_NAME, PLAYER_UUID,
                YGGDRASIL, PLAYER_UUID.toString(), "Drasl", null, 0L, "fake-client-token"
        );

        assertThatThrownBy(() -> TaskScheduler.submitParallel(() -> provider(fake).refresh(account)).get())
                .hasRootCauseInstanceOf(InjectorAuthException.class)
                .rootCause()
                .hasMessageContaining("ForbiddenOperationException")
                .hasMessageContaining("Invalid token")
                .hasMessageNotContaining(ACCESS_TOKEN);
    }

    private static JsonObject legacyAccountJson() {
        JsonObject legacy = new JsonObject();
        legacy.addProperty("accessToken", ACCESS_TOKEN);
        legacy.addProperty("playerName", PLAYER_NAME);
        legacy.addProperty("playerUUID", PLAYER_UUID.toString());
        legacy.addProperty("server", YGGDRASIL);
        legacy.addProperty("loginToken", ACCESS_TOKEN);
        legacy.addProperty("preferredPlayerUUID", PLAYER_UUID.toString());
        legacy.addProperty("type", "injector.authlib-injector");
        legacy.addProperty("accountName", "Drasl");
        return legacy;
    }
}
