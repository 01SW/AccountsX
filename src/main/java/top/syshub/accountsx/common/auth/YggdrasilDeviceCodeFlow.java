package top.syshub.accountsx.common.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.function.BooleanSupplier;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.utils.AvatarService;
import top.syshub.accountsx.common.utils.Threading;

/**
 * Yggdrasil device-code（OAuth）流程（P3.1：从 {@code AbstractInjectorAccountProvider} 拆出）。
 * 端点与 clientId 取自注册表条目；条目缺字段时才回退到"GET 根 + openid-configuration"探测
 * （旧启动器写的 servers[] 没有 device 端点、或启动预热时服务器宕过机）。
 *
 * <p>流程拆成两段以支撑「先展示验证码、用户完成授权后再开局」的界面：
 * {@link #requestDeviceCode} 只换设备码，{@link #awaitToken} 负责轮询 token。
 * {@link #login} 是两者的一次性组合，保留给旧的单表单登录路径。</p>
 */
public final class YggdrasilDeviceCodeFlow {

    private static volatile AuthServerRegistry registry;

    public static void installRegistry(AuthServerRegistry r) {
        registry = r;
    }

    private static AuthServerRegistry registry() {
        if (registry == null)
            throw new IllegalStateException("AuthServerRegistry 未注入。");
        return registry;
    }
    /** 设备码流程的 UI/等待副作用；P3.1 抽成参数使登录编排可单测（无需 MC 适配器）。 */
    public interface Prompts {
        void toast(String titleKey, String descKey, Object... args);

        void copyText(String text);

        void openBrowser(String url);

        void sleepMillis(long millis) throws IOException;
    }

    /** 休眠抽象：单测要快进、界面要能在关闭后立刻放弃，都不能依赖裸 {@link Thread#sleep}。 */
    public interface Sleep {
        void sleep(long millis) throws IOException;
    }

    /**
     * 两阶段登录的中间结果。
     *
     * <p>端点与 clientId 一起带上，轮询阶段因此不必回查注册表，也不会在两次请求之间被注册表更新影响。</p>
     */
    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
            String verificationUriComplete, int interval, int expiresIn,
            String clientId, String tokenEndpoint) {

        /** 优先用带 code 的完整地址，用户不必再手工输入。 */
        public String browserUrl() {
            return verificationUriComplete != null ? verificationUriComplete : verificationUri;
        }
    }

    public static final Prompts PLATFORM_PROMPTS = new Prompts() {
        @Override
        public void toast(String titleKey, String descKey, Object... args) {
            top.syshub.accountsx.common.adapters.Platforms.getMinecraftPlatform().showToast(titleKey, descKey, args);
        }

        @Override
        public void copyText(String text) {
            top.syshub.accountsx.common.adapters.Platforms.getMinecraftPlatform().copyText(text);
        }

        @Override
        public void openBrowser(String url) {
            top.syshub.accountsx.common.adapters.Platforms.getMinecraftPlatform().openBrowser(url);
        }

        @Override
        public void sleepMillis(long millis) throws IOException {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted.", e);
            }
        }
    };

    private static final String SCOPE =
            "openid offline_access Yggdrasil.PlayerProfiles.Select Yggdrasil.Server.Join";

    private YggdrasilDeviceCodeFlow() {
    }

    /** 缓存端点是否足以驱动 device-code：缺 device / token 端点即视为该服务器不支持 OAuth。 */
    public static boolean supportsDeviceCode(AuthServer server) {
        OAuthEndpoints endpoints = server.oauth();
        return endpoints != null && endpoints.clientId() != null && endpoints.tokenEndpoint() != null
                && endpoints.deviceAuthorizationEndpoint() != null;
    }

    /** 第一阶段：只换设备码，不做任何端点探测（缺端点直接失败，由界面退回密码登录）。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static DeviceCode requestDeviceCode(HttpGateway http, AuthServer server) throws IOException {
        if (!supportsDeviceCode(server))
            throw new IOException("This server does not support OAuth login.");
        OAuthEndpoints endpoints = server.oauth();

        JsonObject device = http.postForm(endpoints.deviceAuthorizationEndpoint(),
                Map.of("client_id", endpoints.clientId(), "scope", SCOPE));
        return new DeviceCode(
                device.get("device_code").getAsString(),
                device.get("user_code").getAsString(),
                str(device.get("verification_uri")),
                str(device.get("verification_uri_complete")),
                Math.max(intOr(device.get("interval"), 5), 1),
                Math.max(intOr(device.get("expires_in"), 300), 1),
                endpoints.clientId(),
                endpoints.tokenEndpoint());
    }

    /**
     * 第二阶段：轮询 token 端点直到用户完成授权。
     *
     * <p>{@code cancelled} 为真（用户关掉了登录界面）时抛 {@link IOException}——调用方已经把界面
     * 判为失效，迟到的结果本来就会被丢弃，这里只是让轮询尽快停下来。</p>
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static YggdrasilAuthResult awaitToken(HttpGateway http, AuthServer server, DeviceCode code,
            Sleep sleep, BooleanSupplier cancelled) throws IOException {
        long stepMillis = Math.max(code.interval(), 1) * 1000L;
        long deadline = System.nanoTime() + Math.max(code.expiresIn(), 1) * 1_000_000_000L;
        String accessToken = null, refreshToken = null, idToken = null;

        while (System.nanoTime() < deadline) {
            sleep.sleep(stepMillis);
            if (cancelled.getAsBoolean())
                throw new IOException("Cancelled.");

            JsonObject token = http.postForm(code.tokenEndpoint(), Map.of(
                    "client_id", code.clientId(),
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code", code.deviceCode()), true);

            JsonElement err = token.get("error");
            if (err == null) {
                accessToken = token.get("access_token").getAsString();
                refreshToken = token.get("refresh_token").getAsString();
                idToken = token.get("id_token").getAsString();
                break;
            }

            String error = err.getAsString();
            if (error.equals("authorization_pending") || error.equals("slow_down"))
                continue;
            if (error.equals("access_denied"))
                throw new IOException("Authorization denied.");
            if (error.equals("expired_token"))
                throw new IOException("No character detected.");
            throw new IOException("Unknown error: " + error);
        }

        if (accessToken == null || refreshToken == null || idToken == null)
            throw new IOException("Invalid token.");

        return toResult(http, server.apiRoot(), accessToken, refreshToken, idToken,
                code.tokenEndpoint(), code.clientId());
    }

    /** 可被打断的休眠：按 200 ms 切片检查取消标志，关窗后不必等满一个 interval 才停。 */
    public static Sleep cancellableSleep(BooleanSupplier cancelled) {
        return millis -> {
            long remaining = millis;
            while (remaining > 0) {
                if (cancelled.getAsBoolean())
                    throw new IOException("Cancelled.");
                long slice = Math.min(remaining, 200L);
                try {
                    Thread.sleep(slice);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted.", e);
                }
                remaining -= slice;
            }
        };
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static YggdrasilAuthResult login(HttpGateway http, Prompts prompts, AuthServer server,
            Map<String, String> knownClientIds) throws IOException {
        AuthServer active = server;
        if (!supportsDeviceCode(server)) {
            OAuthEndpoints discovered = discover(http, server.apiRoot(), knownClientIds);
            active = new AuthServer(server.apiRoot(), server.kind(), server.displayName(), server.locator(),
                    discovered, server.cachedAt());
        }

        prompts.toast("accountsx.account.oauth2.code.generating", null);
        DeviceCode code = requestDeviceCode(http, active);

        String url = code.browserUrl();
        if (url == null)
            throw new IOException("Invalid verification url.");
        prompts.openBrowser(url);
        // 有 verification_uri_complete 时把整条链接放进剪贴板（用户可能在别的机器上打开），
        // 否则只能复制验证码。
        prompts.copyText(code.verificationUriComplete() != null ? url : code.userCode());
        prompts.toast("accountsx.account.oauth2.code.title", "accountsx.account.oauth2.code.desc", code.userCode());

        YggdrasilAuthResult result = awaitToken(http, active, code, prompts::sleepMillis, () -> false);
        // 探测到的端点回写注册表，下次登录（含新界面）不再探测。
        if (active != server)
            registry().updateOAuthEndpoints(server.apiRoot(), active.oauth());
        return result;
    }

    /** OAuth 刷新（信封 JSON 即 loginToken 去掉前缀的部分）。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static YggdrasilAuthResult refresh(HttpGateway http, String envelopeJson, String apiRoot)
            throws IOException {
        JsonObject envelope = JsonParser.parseString(envelopeJson).getAsJsonObject();
        String tokenEndpoint = envelope.get("token_endpoint").getAsString();
        String refreshToken = envelope.get("refresh_token").getAsString();
        String clientId = envelope.get("client_id").getAsString();

        Map<String, String> form = Map.of(
                "client_id", clientId,
                "grant_type", "refresh_token",
                "refresh_token", refreshToken
        );
        JsonObject token = http.postForm(tokenEndpoint, form);

        JsonElement err = token.get("error");
        if (err != null)
            throw new IOException("Unknown error: " + err.getAsString());
        String accessToken = token.get("access_token").getAsString();
        String newRefresh = token.get("refresh_token").getAsString();
        String idToken = token.get("id_token").getAsString();

        return toResult(http, apiRoot, accessToken, newRefresh, idToken, tokenEndpoint, clientId);
    }

    /** token 响应 → 账号数据：id_token 给身份，sessionserver 给头像。 */
    private static YggdrasilAuthResult toResult(HttpGateway http, String apiRoot, String accessToken,
            String refreshToken, String idToken, String tokenEndpoint, String clientId) throws IOException {
        YggdrasilProfiles.Profile profile = readIdTokenProfile(idToken);
        AvatarService.AvatarKey avatar = AvatarService.fetch(http,
                apiRoot + "/sessionserver/session/minecraft/profile/", profile.playerUuid());
        return new YggdrasilAuthResult(accessToken, profile.playerName(), profile.playerUuid(),
                refreshToken, tokenEndpoint, clientId,
                avatar == null ? null : avatar.key(), avatar == null ? 0L : avatar.cachedAt());
    }

    static YggdrasilProfiles.Profile readIdTokenProfile(String idToken) throws IOException {
        String[] parts = idToken.split("\\.");
        if (parts.length < 2)
            throw new IOException("Invalid id token.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        JsonObject userinfo = JsonParser.parseString(payload).getAsJsonObject();
        return YggdrasilProfiles.readProfiles(userinfo).get(0);
    }

    /** 兜底发现：GET 根路径取 openid_configuration_url，再取端点与 clientId（内置 host 表 > YGGC 的 shared_client_id）。 */
    static OAuthEndpoints discover(HttpGateway http, String apiRoot, Map<String, String> knownClientIds)
            throws IOException {
        JsonObject ygg = http.get(apiRoot);
        String openidConfigurationUrl;
        if (ygg.get("meta") instanceof JsonObject meta
                && meta.get("feature.openid_configuration_url") instanceof JsonPrimitive jp1 && jp1.isString())
            openidConfigurationUrl = jp1.getAsString();
        else
            throw new IOException("Invalid openid configuration url!");

        JsonObject config = http.get(openidConfigurationUrl);
        String deviceEndpoint = config.get("device_authorization_endpoint").getAsString();
        String tokenEndpoint = config.get("token_endpoint").getAsString();
        String userinfo = str(config.get("userinfo_endpoint"));
        String clientId;
        String host = URI.create(apiRoot).getHost();
        String known = knownClientIds == null ? null : knownClientIds.get(host);
        clientId = known != null ? known : YggdrasilServerProbe.sharedClientId(config);
        if (clientId == null)
            throw new IOException("Invalid client id!");
        return new OAuthEndpoints(clientId, tokenEndpoint, deviceEndpoint, userinfo);
    }

    private static int intOr(JsonElement e, int fallback) {
        return e instanceof JsonPrimitive p && p.isNumber() ? p.getAsInt() : fallback;
    }

    private static String str(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }
}
