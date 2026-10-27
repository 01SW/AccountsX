package top.syshub.accountsx.common.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider;
import top.syshub.accountsx.common.net.JdkHttpGateway;
import top.syshub.accountsx.common.task.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 两步式 device-code（P5 外置登录界面所用 API）：取码与轮询分离，取消可打断。
 * fixture 一律 fake- 前缀，无任何真实凭据。
 */
class DeviceCodeFlowTest {
    private static final String UUID32 = "069a79f444e94726a5befca90e38aaf5";

    private HttpServer server;
    private String base;
    private String apiRoot;
    private AuthServer authServer;
    private final AtomicInteger deviceHits = new AtomicInteger();
    private final AtomicInteger tokenHits = new AtomicInteger();
    /** true 时 token 端点一直回 authorization_pending，用于验证取消。 */
    private volatile boolean pendingForever;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        apiRoot = base + "/api/yggdrasil";

        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            JsonObject body = null;
            int code = 200;
            switch (key) {
                case "POST /api/yggdrasil/oauth/device" -> {
                    deviceHits.incrementAndGet();
                    body = new JsonObject();
                    body.addProperty("device_code", "fake-device");
                    body.addProperty("user_code", "FAKE-CODE");
                    body.addProperty("verification_uri", base + "/oauth/verify");
                    body.addProperty("verification_uri_complete", base + "/oauth/verify?code=FAKE-CODE");
                    body.addProperty("interval", 0);
                    body.addProperty("expires_in", 0);
                }
                case "POST /api/yggdrasil/oauth/token" -> {
                    tokenHits.incrementAndGet();
                    body = new JsonObject();
                    if (pendingForever) {
                        body.addProperty("error", "authorization_pending");
                    } else {
                        body.addProperty("access_token", "fake-access2");
                        body.addProperty("refresh_token", "fake-refresh2");
                        body.addProperty("id_token", fakeIdToken());
                    }
                }
                case "GET /api/yggdrasil/sessionserver/session/minecraft/profile/" + UUID32 -> {
                    body = new JsonObject();
                    body.add("properties", new JsonArray());
                }
                default -> code = 404;
            }
            byte[] bytes = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
            if (body != null) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        AuthServerRegistry registry = new AuthServerRegistry(JdkHttpGateway.INSTANCE);
        AbstractInjectorAccountProvider.installRegistry(registry);
        authServer = new AuthServer(apiRoot, AuthServerKind.YGGDRASIL, "FakeInjector",
                new OAuthEndpoints("fake-shared-client", apiRoot + "/oauth/token", apiRoot + "/oauth/device", null), 0L);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private static String fakeIdToken() {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"availableProfiles\":[{\"name\":\"Alex\",\"id\":\""
                + UUID32 + "\"}]}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".fakesig";
    }

    @Test
    void supportsDeviceCode_requiresAllThreeEndpoints() {
        assertThat(YggdrasilDeviceCodeFlow.supportsDeviceCode(authServer)).isTrue();
        assertThat(YggdrasilDeviceCodeFlow.supportsDeviceCode(new AuthServer(apiRoot, AuthServerKind.YGGDRASIL,
                "FakeInjector", new OAuthEndpoints("fake-client", apiRoot + "/oauth/token", null, null), 0L)))
                .isFalse();
        assertThat(YggdrasilDeviceCodeFlow.supportsDeviceCode(new AuthServer(apiRoot, AuthServerKind.YGGDRASIL,
                "FakeInjector", null, 0L))).isFalse();
    }

    @Test
    void requestDeviceCode_clampsTimingsAndPrefersCompleteUri() throws Exception {
        YggdrasilDeviceCodeFlow.DeviceCode code = YggdrasilDeviceCodeFlow.requestDeviceCode(JdkHttpGateway.INSTANCE, authServer);

        assertThat(code.deviceCode()).isEqualTo("fake-device");
        assertThat(code.userCode()).isEqualTo("FAKE-CODE");
        assertThat(code.clientId()).isEqualTo("fake-shared-client");
        assertThat(code.tokenEndpoint()).isEqualTo(apiRoot + "/oauth/token");
        // 服务端给 0 也不能忙等：至少 1 秒
        assertThat(code.interval()).isEqualTo(1);
        assertThat(code.expiresIn()).isEqualTo(1);
        assertThat(code.browserUrl()).isEqualTo(base + "/oauth/verify?code=FAKE-CODE");
        assertThat(deviceHits).hasValue(1);
    }

    @Test
    void awaitToken_returnsResultAndCancelledSupplierAborts() throws Exception {
        YggdrasilDeviceCodeFlow.DeviceCode code = YggdrasilDeviceCodeFlow.requestDeviceCode(JdkHttpGateway.INSTANCE, authServer);

        YggdrasilAuthResult result = YggdrasilDeviceCodeFlow.awaitToken(JdkHttpGateway.INSTANCE, authServer, code,
                millis -> { }, () -> false);
        assertThat(result.accessToken()).isEqualTo("fake-access2");
        assertThat(result.refreshToken()).isEqualTo("fake-refresh2");
        assertThat(result.playerName()).isEqualTo("Alex");

        pendingForever = true;
        assertThatThrownBy(() -> YggdrasilDeviceCodeFlow.awaitToken(JdkHttpGateway.INSTANCE, authServer, code,
                millis -> { }, () -> true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Cancelled");
    }

    @Test
    void loginDeviceCode_createsAccountWithoutProbing() throws Exception {
        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE);
        YggdrasilDeviceCodeFlow.DeviceCode code = provider.fetchDeviceCode(authServer);

        AbstractInjectorAccount[] box = new AbstractInjectorAccount[1];
        // setLoginProfile 要求 worker 线程：与生产一致地在调度器上跑
        TaskScheduler.submit(() -> box[0] = provider.loginDeviceCode(authServer, code, () -> false)).get();

        AbstractInjectorAccount account = box[0];
        assertThat(account.getServer()).isEqualTo(apiRoot);
        assertThat(account.getAccountStorage().getPlayerName()).isEqualTo("Alex");
        assertThat(account.getLoginToken()).startsWith(AccountOAuth.PREFIX).contains("fake-refresh2");
        assertThat(deviceHits).hasValue(1);
        assertThat(tokenHits).hasValue(1);
    }
}
