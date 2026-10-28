package top.syshub.accountsx.common.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.impl.injector.InjectorAuthException;
import top.syshub.accountsx.common.net.HttpGateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 认证失败必须给出可操作提示，而不是「HTTP 403」或 NPE。
 *
 * <p>Yggdrasil 的失败是 200/403 + {@code error}/{@code errorMessage} 的 JSON 错误体，
 * 因此请求必须以 {@code ignoreHttpStatus=true} 发出，否则错误体读不到。</p>
 */
class YggdrasilPasswordFlowTest {

    private static final String API_ROOT = "https://ygg.invalid/api/yggdrasil";
    private static final AuthServer SERVER =
            new AuthServer(API_ROOT, AuthServerKind.YGGDRASIL, "FakeInjector", null, 0L);

    /** 返回固定响应、并记录最后一次请求的假网关。 */
    private static final class FakeGateway implements HttpGateway {
        private JsonObject response;
        private JsonElement lastBody;
        private String lastUrl;
        private boolean lastIgnoreHttpStatus;

        FakeGateway(JsonObject response) {
            this.response = response;
        }

        @Override
        public JsonObject get(String url) {
            throw new UnsupportedOperationException("fake: get not expected");
        }

        @Override
        public JsonObject get(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException("fake: get(headers) not expected");
        }

        @Override
        public JsonObject postJson(String url, JsonElement body) {
            return postJson(url, body, false);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) {
            this.lastUrl = url;
            this.lastBody = body;
            this.lastIgnoreHttpStatus = ignoreHttpStatus;
            return response;
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData) {
            throw new UnsupportedOperationException("fake: postForm not expected");
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException("fake: postForm(ignore) not expected");
        }

        @Override
        public Headers getHeaders(String url) {
            throw new UnsupportedOperationException("fake: getHeaders not expected");
        }

        @Override
        public byte[] getBinary(String url) {
            throw new UnsupportedOperationException("fake: getBinary not expected");
        }
    }

    /** 按 URL 分派响应、并按 URL 记录请求体的假网关：一条登录流程会打两个端点。 */
    private static final class RoutingGateway implements HttpGateway {
        private final Map<String, JsonObject> responses = new LinkedHashMap<>();
        private final Map<String, JsonElement> bodies = new LinkedHashMap<>();

        RoutingGateway reply(String path, JsonObject response) {
            this.responses.put(API_ROOT + path, response);
            return this;
        }

        @Override
        public JsonObject get(String url) {
            // 头像解析在 AvatarService 内部吞异常，这里直接拒绝即可。
            throw new UnsupportedOperationException("fake: get not expected");
        }

        @Override
        public JsonObject get(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException("fake: get(headers) not expected");
        }

        @Override
        public JsonObject postJson(String url, JsonElement body) {
            return postJson(url, body, false);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) {
            this.bodies.put(url, body);
            JsonObject response = this.responses.get(url);
            if (response == null) {
                throw new AssertionError("unexpected POST " + url);
            }
            return response;
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData) {
            throw new UnsupportedOperationException("fake: postForm not expected");
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException("fake: postForm(ignore) not expected");
        }

        @Override
        public Headers getHeaders(String url) {
            throw new UnsupportedOperationException("fake: getHeaders not expected");
        }

        @Override
        public byte[] getBinary(String url) {
            throw new UnsupportedOperationException("fake: getBinary not expected");
        }

        JsonObject body(String path) {
            JsonElement body = this.bodies.get(API_ROOT + path);
            assertThat(body).as("no POST to " + path).isNotNull();
            return body.getAsJsonObject();
        }

        boolean posted(String path) {
            return this.bodies.containsKey(API_ROOT + path);
        }
    }

    private static JsonObject error(String error, String errorMessage) {
        JsonObject json = new JsonObject();
        json.addProperty("error", error);
        json.addProperty("errorMessage", errorMessage);
        return json;
    }

    private static JsonObject profile(String name, String uuid) {
        JsonObject profile = new JsonObject();
        profile.addProperty("name", name);
        profile.addProperty("id", uuid);
        return profile;
    }

    private static JsonObject authenticated(String accessToken, JsonArray available) {
        JsonObject json = new JsonObject();
        json.addProperty("accessToken", accessToken);
        json.addProperty("clientToken", "fake-client-token");
        if (available != null) {
            json.add("availableProfiles", available);
        }
        return json;
    }

    private static JsonArray profiles(JsonObject... entries) {
        JsonArray array = new JsonArray();
        for (JsonObject entry : entries) {
            array.add(entry);
        }
        return array;
    }

    @Test
    void login_errorBody_becomesActionableException() {
        FakeGateway fake = new FakeGateway(error("ForbiddenOperationException", "Invalid credentials"));

        assertThatThrownBy(() -> YggdrasilPasswordFlow.login(fake, SERVER, "fake-user", "fake-pass"))
                .isInstanceOf(InjectorAuthException.class)
                .hasMessageContaining("Invalid credentials")
                .extracting(e -> ((InjectorAuthException) e).getTranslationKey())
                .isEqualTo("accountsx.account.fail.injector_login");

        // 必须忽略 HTTP 状态码，否则网关会先把 403 变成 IOException，错误体读不到。
        assertThat(fake.lastIgnoreHttpStatus).isTrue();
        assertThat(fake.lastUrl).isEqualTo(API_ROOT + "/authserver/authenticate");
        assertThat(fake.lastBody.getAsJsonObject().get("clientToken").getAsString()).hasSize(32);
    }

    @Test
    void login_responseWithoutAccessToken_isIOExceptionNotNpe() {
        // drasl 的 500 形状：只有 errorMessage，没有 error 字段 —— throwIfError 放行，取值不能 NPE。
        JsonObject body = new JsonObject();
        body.addProperty("path", "/authserver/authenticate");
        body.addProperty("errorMessage", "internal server error");
        FakeGateway fake = new FakeGateway(body);

        assertThatThrownBy(() -> YggdrasilPasswordFlow.login(fake, SERVER, "fake-user", "fake-pass"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("does not contain a string accessToken")
                .isNotInstanceOf(InjectorAuthException.class);
    }

    @Test
    void authenticate_multiProfile_returnsDraftWithoutRefreshing() throws Exception {
        RoutingGateway fake = new RoutingGateway()
                .reply("/authserver/authenticate", authenticated("fake-access",
                        profiles(profile("Alex", "11111111111111111111111111111111"),
                                profile("Steve", "22222222222222222222222222222222"))));

        YggdrasilPasswordFlow.LoginDraft draft =
                YggdrasilPasswordFlow.authenticate(fake, SERVER, "fake-user", "fake-pass");

        assertThat(draft.profiles()).extracting(YggdrasilProfiles.Profile::playerName)
                .containsExactly("Alex", "Steve");
        assertThat(draft.bound()).isNull();
        // 关键行为：认证阶段不许替用户选角色，所以不能有任何 refresh 请求。
        assertThat(fake.posted("/authserver/refresh")).isFalse();
    }

    @Test
    void bind_sendsSelectedProfileAndKeepsBoundToken() throws Exception {
        String steve = "22222222222222222222222222222222";
        RoutingGateway fake = new RoutingGateway()
                .reply("/authserver/authenticate", authenticated("fake-access",
                        profiles(profile("Alex", "11111111111111111111111111111111"),
                                profile("Steve", steve))))
                .reply("/authserver/refresh", authenticated("fake-bound-access", null));

        YggdrasilPasswordFlow.LoginDraft draft =
                YggdrasilPasswordFlow.authenticate(fake, SERVER, "fake-user", "fake-pass");
        YggdrasilAuthResult result = YggdrasilPasswordFlow.bind(fake, SERVER, draft,
                new YggdrasilProfiles.Profile("Steve", steve));

        JsonObject body = fake.body("/authserver/refresh");
        assertThat(body.getAsJsonObject("selectedProfile").get("id").getAsString()).isEqualTo(steve);
        assertThat(body.getAsJsonObject("selectedProfile").get("name").getAsString()).isEqualTo("Steve");
        assertThat(body.get("clientToken").getAsString()).isEqualTo(draft.clientToken());
        // 绑定后的令牌才是这个角色专属的，必须用它建账号。
        assertThat(result.accessToken()).isEqualTo("fake-bound-access");
        assertThat(result.playerUuid().replace("-", "")).isEqualTo(steve);
    }

    @Test
    void single_multiProfileWithoutBound_requiresSelection() throws Exception {
        RoutingGateway fake = new RoutingGateway()
                .reply("/authserver/authenticate", authenticated("fake-access",
                        profiles(profile("Alex", "11111111111111111111111111111111"),
                                profile("Steve", "22222222222222222222222222222222"))));

        YggdrasilPasswordFlow.LoginDraft draft =
                YggdrasilPasswordFlow.authenticate(fake, SERVER, "fake-user", "fake-pass");

        assertThatThrownBy(() -> YggdrasilPasswordFlow.single(fake, SERVER, draft))
                .isInstanceOf(ProfileSelectionRequiredException.class)
                .satisfies(e -> assertThat(((ProfileSelectionRequiredException) e).getProfiles()).hasSize(2));
        // 不许「猜」一个角色回来：多角色账号必须由用户选。
        assertThat(fake.posted("/authserver/refresh")).isFalse();
    }

    @Test
    void single_singleProfile_bindsThroughRefresh() throws Exception {
        RoutingGateway fake = new RoutingGateway()
                .reply("/authserver/authenticate", authenticated("fake-access",
                        profiles(profile("Alex", "11111111111111111111111111111111"))))
                .reply("/authserver/refresh", authenticated("fake-bound-access", null));

        YggdrasilAuthResult result = YggdrasilPasswordFlow.login(fake, SERVER, "fake-user", "fake-pass");

        assertThat(fake.body("/authserver/refresh").getAsJsonObject("selectedProfile")
                .get("id").getAsString()).isEqualTo("11111111111111111111111111111111");
        assertThat(result.accessToken()).isEqualTo("fake-bound-access");
    }

    @Test
    void single_serverAlreadyBound_doesNotRefreshAgain() throws Exception {
        // 服务端在认证响应里就给了 selectedProfile：令牌已经绑好了，再刷新一次纯属浪费。
        JsonObject body = authenticated("fake-access", null);
        body.add("selectedProfile", profile("Alex", "11111111111111111111111111111111"));
        RoutingGateway fake = new RoutingGateway().reply("/authserver/authenticate", body);

        YggdrasilAuthResult result = YggdrasilPasswordFlow.login(fake, SERVER, "fake-user", "fake-pass");

        assertThat(fake.posted("/authserver/refresh")).isFalse();
        assertThat(result.accessToken()).isEqualTo("fake-access");
        assertThat(result.playerName()).isEqualTo("Alex");
    }

    @Test
    void authenticate_availableProfilesWithBound_keepsBoth_forPreselection() throws Exception {
        // availableProfiles 有两个角色、同时服务端已 bound 其中一个：两个事实都要保留，
        // 界面才能列出全部角色并把已绑的那个预先勾上。
        JsonObject body = authenticated("fake-access",
                profiles(profile("Alex", "11111111111111111111111111111111"),
                        profile("Steve", "22222222222222222222222222222222")));
        body.add("selectedProfile", profile("Steve", "22222222222222222222222222222222"));
        RoutingGateway fake = new RoutingGateway().reply("/authserver/authenticate", body);

        YggdrasilPasswordFlow.LoginDraft draft =
                YggdrasilPasswordFlow.authenticate(fake, SERVER, "fake-user", "fake-pass");

        assertThat(draft.profiles()).hasSize(2);
        assertThat(draft.bound()).isNotNull();
        assertThat(draft.bound().playerName()).isEqualTo("Steve");
        assertThat(draft.isBoundTo(new YggdrasilProfiles.Profile("Steve",
                "22222222222222222222222222222222"))).isTrue();
        assertThat(draft.isBoundTo(new YggdrasilProfiles.Profile("Alex",
                "11111111111111111111111111111111"))).isFalse();
    }

    @Test
    void authenticate_noProfiles_isActionableException() {
        RoutingGateway fake = new RoutingGateway()
                .reply("/authserver/authenticate", authenticated("fake-access", null));

        assertThatThrownBy(() -> YggdrasilPasswordFlow.authenticate(fake, SERVER, "fake-user", "fake-pass"))
                .isInstanceOf(InjectorAuthException.class)
                .hasMessageContaining("does not contain any player profile")
                .extracting(e -> ((InjectorAuthException) e).getTranslationKey())
                .isEqualTo("accountsx.account.fail.injector_login");
    }
}
