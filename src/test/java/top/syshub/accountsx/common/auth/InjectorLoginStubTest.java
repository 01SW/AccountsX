package top.syshub.accountsx.common.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccountProvider;
import top.syshub.accountsx.common.auth.AuthServerRegistry;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider;
import top.syshub.accountsx.common.net.JdkHttpGateway;
import top.syshub.accountsx.common.task.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3.1 端到端（05 文档 1.4b 最简 Yggdrasil stub）：密码登录 / device-code 登录 / OAuth 刷新
 * 全部打真 HTTP stub，断言请求次数——"同服务器多账号只探测一次"（03 路线图 P3.1 验收）。
 * fixture 一律 fake- 前缀，无任何真实凭据。
 */
class InjectorLoginStubTest {
    private AuthServerRegistry registry;
    private static final String UUID32 = "069a79f444e94726a5befca90e38aaf5";

    private HttpServer server;
    private String base;
    private String apiRoot;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    private static void bump(Map<String, AtomicInteger> hits, String key) {
        hits.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private int count(String key) {
        return hits.getOrDefault(key, new AtomicInteger()).get();
    }

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        apiRoot = base + "/api/yggdrasil";

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String key = method + " " + path;
            bump(hits, key);
            JsonObject body = null;
            int code = 200;
            switch (key) {
                case "HEAD /" -> {
                    exchange.getResponseHeaders().add("X-Authlib-Injector-API-Location", "/api/yggdrasil");
                }
                case "GET /api/yggdrasil" -> {
                    try {
                        body = YggdrasilFixtures.rootMeta(apiRoot);
                    } catch (IOException e) {
                        code = 500;
                    }
                }
                case "GET /api/yggdrasil/.well-known/openid" -> body = YggdrasilFixtures.openidConfig(apiRoot);
                case "POST /api/yggdrasil/authserver/authenticate" -> body = YggdrasilFixtures.profile("Steve", UUID32);
                case "POST /api/yggdrasil/oauth/device" -> {
                    body = new JsonObject();
                    body.addProperty("device_code", "fake-device");
                    body.addProperty("user_code", "FAKE-CODE");
                    body.addProperty("verification_uri", base + "/oauth/verify");
                    body.addProperty("interval", 1);
                    body.addProperty("expires_in", 1);
                }
                case "POST /api/yggdrasil/oauth/token" -> {
                    body = new JsonObject();
                    body.addProperty("access_token", "fake-access2");
                    body.addProperty("refresh_token", "fake-refresh2");
                    body.addProperty("id_token", fakeIdToken());
                }
                case "GET /api/yggdrasil/sessionserver/session/minecraft/profile/" + UUID32 -> {
                    body = new JsonObject();
                    body.add("properties", new JsonArray());
                }
                default -> {
                    code = 404;
                }
            }
            byte[] bytes = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
            if (body != null)
                exchange.getResponseHeaders().add("Content-Type", "application/json");
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(code, -1);
            } else {
                exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        server.start();
        registry = new AuthServerRegistry(JdkHttpGateway.INSTANCE);
        AbstractInjectorAccountProvider.installRegistry(registry);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
        /* registry cleaned up by test teardown */
    }

    private static String fakeIdToken() {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"availableProfiles\":[{\"name\":\"Alex\",\"id\":\""
                + UUID32 + "\"}]}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".fakesig";
    }

    @Test
    void passwordLogin_reusesServerEntryAcrossTwoLogins() throws Exception {
        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE);
        // 服务器条目由「添加认证服务器」建立：ALI 解析 + 一次探测
        AuthServer server = registry.add(AuthServerKind.YGGDRASIL, base);

        AbstractInjectorAccount[] box = new AbstractInjectorAccount[1];
        TaskScheduler.submit(() -> box[0] = provider.loginPassword(server, "fake-user", "fake-pass")).get();
        AbstractInjectorAccount first = box[0];
        assertThat(first.getServer()).isEqualTo(apiRoot);
        assertThat(first.getAccountStorage().getPlayerName()).isEqualTo("Steve");
        assertThat(first.getLoginToken()).isEqualTo("fake-access");
        assertThat(count("HEAD /")).isEqualTo(1);
        assertThat(count("GET /api/yggdrasil")).isEqualTo(1);
        assertThat(count("POST /api/yggdrasil/authserver/authenticate")).isEqualTo(1);

        TaskScheduler.submit(() -> box[0] = provider.loginPassword(server, "fake-user", "fake-pass")).get();
        AbstractInjectorAccount second = box[0];
        assertThat(second.getServer()).isEqualTo(apiRoot);
        // 登录路径直接拿条目里的端点：第二个账号零探测，只多一次 authenticate
        assertThat(count("GET /api/yggdrasil")).isEqualTo(1);
        assertThat(count("HEAD /")).isEqualTo(1);
        assertThat(count("POST /api/yggdrasil/authserver/authenticate")).isEqualTo(2);

        assertThat(server.displayName()).isEqualTo("FakeInjector");
        assertThat(server.apiRoot()).isEqualTo(apiRoot);
        assertThat(server.oauth().clientId()).isEqualTo("fake-shared-client");

        JsonArray out = new JsonArray();
        registry.persist(out);
        assertThat(out.asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(j -> apiRoot.equals(j.get("apiRoot").getAsString()))).isTrue();
    }

    @Test
    void deviceCodeLogin_usesCachedEndpoints() throws Exception {
        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE);
        AuthServer server = registry.add(AuthServerKind.YGGDRASIL, base);
        AbstractInjectorAccount[] box = new AbstractInjectorAccount[1];
        // setLoginProfile 要求 worker 线程：登录编排在调度器上跑（与生产一致）
        TaskScheduler.submit(() -> {
            YggdrasilDeviceCodeFlow.DeviceCode code = provider.fetchDeviceCode(server);
            box[0] = provider.loginDeviceCode(server, code, () -> false);
        }).get();
        AbstractInjectorAccount account = box[0];

        assertThat(account.getLoginToken()).startsWith(AccountOAuth.PREFIX);
        assertThat(account.getLoginToken()).contains("fake-refresh2");
        assertThat(account.getAccountStorage().getPlayerName()).isEqualTo("Alex");
        assertThat(count("POST /api/yggdrasil/oauth/device")).isEqualTo(1);
        assertThat(count("POST /api/yggdrasil/oauth/token")).isEqualTo(1);
        // 端点来自注册表条目：openid 配置只在加服探测时取过一次
        assertThat(count("GET /api/yggdrasil/.well-known/openid")).isEqualTo(1);
    }

    @Test
    void oauthRefresh_rotatesEnvelope() throws Exception {
        String envelope = AccountOAuth.envelope("fake-refresh", apiRoot + "/oauth/token", "fake-shared-client");
        AuthlibInjectorAccountProvider.AuthlibInjectorAccount account = new AuthlibInjectorAccountProvider.AuthlibInjectorAccount(
                "fake-access", "Alex", java.util.UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                apiRoot, UUID32, "FakeInjector", null, 0L);
        account.setLoginTokenForMigration(envelope, UUID32);

        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE);
        TaskScheduler.submit(() -> provider.refresh(account)).get();

        assertThat(account.getLoginToken()).startsWith(AccountOAuth.PREFIX);
        assertThat(account.getLoginToken()).contains("fake-refresh2");
        assertThat(count("POST /api/yggdrasil/oauth/token")).isEqualTo(1);
    }
}