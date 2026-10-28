package top.syshub.accountsx.common.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.impl.injector.InjectorAuthException;
import top.syshub.accountsx.common.net.HttpGateway;

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

    private static JsonObject error(String error, String errorMessage) {
        JsonObject json = new JsonObject();
        json.addProperty("error", error);
        json.addProperty("errorMessage", errorMessage);
        return json;
    }

    @Test
    void login_errorBody_becomesActionableException() {
        FakeGateway fake = new FakeGateway(error("ForbiddenOperationException", "Invalid credentials"));

        assertThatThrownBy(() -> YggdrasilPasswordFlow.login(fake, SERVER, "fake-user", "fake-pass", ""))
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

        assertThatThrownBy(() -> YggdrasilPasswordFlow.login(fake, SERVER, "fake-user", "fake-pass", ""))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("does not contain a string accessToken")
                .isNotInstanceOf(InjectorAuthException.class);
    }
}
