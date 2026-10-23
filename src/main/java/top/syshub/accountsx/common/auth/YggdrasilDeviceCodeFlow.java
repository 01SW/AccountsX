package top.syshub.accountsx.common.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.Map;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.utils.AvatarService;
import top.syshub.accountsx.common.utils.Threading;

/**
 * Yggdrasil device-code（OAuth）流程（P3.1：从 {@code AbstractInjectorAccountProvider} 拆出）。
 * 端点与 clientId 取自注册表条目；条目缺字段时才回退到"GET 根 + openid-configuration"探测
 * （旧启动器写的 servers[] 没有 device 端点、或启动预热时服务器宕过机）。
 */
public final class YggdrasilDeviceCodeFlow {
    /** 设备码流程的 UI/等待副作用；P3.1 抽成参数使登录编排可单测（无需 MC 适配器）。 */
    public interface Prompts {
        void toast(String titleKey, String descKey, Object... args);

        void copyText(String text);

        void openBrowser(String url);

        void sleepMillis(long millis) throws IOException;
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

    private YggdrasilDeviceCodeFlow() {
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static YggdrasilAuthResult login(HttpGateway http, Prompts prompts, AuthServer server,
            Map<String, String> knownClientIds) throws IOException {
        OAuthEndpoints endpoints = server.oauth();
        boolean discovered = false;
        if (endpoints == null || endpoints.clientId() == null
                || endpoints.deviceAuthorizationEndpoint() == null || endpoints.tokenEndpoint() == null) {
            endpoints = discover(http, server.apiRoot(), knownClientIds);
            discovered = true;
        }
        String tokenEndpoint = endpoints.tokenEndpoint();
        String clientId = endpoints.clientId();

        prompts.toast("accountsx.account.oauth2.code.generating", null);

        Map<String, String> form1 = Map.of(
                "client_id", clientId,
                "scope", "openid offline_access Yggdrasil.PlayerProfiles.Select Yggdrasil.Server.Join"
        );
        JsonObject device = http.postForm(endpoints.deviceAuthorizationEndpoint(), form1);
        String deviceCode = device.get("device_code").getAsString();
        String userCode = device.get("user_code").getAsString();
        int interval = intOr(device.get("interval"), 5);
        int expires = intOr(device.get("expires_in"), 300);
        if (device.get("verification_uri_complete") instanceof JsonPrimitive jp && jp.isString()) {
            prompts.openBrowser(jp.getAsString());
            prompts.copyText(jp.getAsString());
        } else {
            prompts.copyText(userCode);
            // 1.10 修复：无 verification_uri_complete 时打开 verification_uri
            prompts.openBrowser(device.get("verification_uri").getAsString());
        }
        prompts.toast("accountsx.account.oauth2.code.title", "accountsx.account.oauth2.code.desc", userCode);

        String accessToken = null, refreshToken = null, idToken = null;
        for (int i = 0; i < expires; i += interval) {
            prompts.sleepMillis(Math.max(interval, 1) * 1000L);

            Map<String, String> form2 = Map.of(
                    "client_id", clientId,
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code", deviceCode
            );
            JsonObject token = http.postForm(tokenEndpoint, form2, true);

            JsonElement err = token.get("error");
            if (err == null) {
                accessToken = token.get("access_token").getAsString();
                refreshToken = token.get("refresh_token").getAsString();
                idToken = token.get("id_token").getAsString();
                break;
            }

            String error = err.getAsString();
            if (error.equals("authorization_pending"))
                continue;
            if (error.equals("expired_token"))
                throw new IOException("No character detected.");
            throw new IOException("Unknown error: " + error);
        }

        if (accessToken == null || refreshToken == null || idToken == null)
            throw new IOException("Invalid token.");

        YggdrasilProfiles.Profile profile = readIdTokenProfile(idToken);
        String apiRoot = server.apiRoot();
        if (discovered)
            AuthServers.current().updateOAuthEndpoints(apiRoot, endpoints);
        AvatarService.AvatarKey avatar = AvatarService.fetch(http,
                apiRoot + "/sessionserver/session/minecraft/profile/", profile.playerUuid());
        return new YggdrasilAuthResult(accessToken, profile.playerName(), profile.playerUuid(),
                refreshToken, tokenEndpoint, clientId,
                avatar == null ? null : avatar.key(), avatar == null ? 0L : avatar.cachedAt());
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
        YggdrasilProfiles.Profile profile = readIdTokenProfile(idToken);

        AvatarService.AvatarKey avatar = AvatarService.fetch(http,
                apiRoot + "/sessionserver/session/minecraft/profile/", profile.playerUuid());
        return new YggdrasilAuthResult(accessToken, profile.playerName(), profile.playerUuid(),
                newRefresh, tokenEndpoint, clientId,
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

    /** 兜底发现：GET 根路径取 openid_configuration_url，再取端点与 clientId（host 表 > shared_client_id）。 */
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
        if (known != null)
            clientId = known;
        else if (config.get("shared_client_id") instanceof JsonPrimitive jp2 && jp2.isString())
            clientId = jp2.getAsString();
        else
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
