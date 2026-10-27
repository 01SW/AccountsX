package top.syshub.accountsx.common.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.utils.NetworkUtils;

public final class YggdrasilServerProbe {
    private static final int MAX_API_LOCATION_HOPS = 10;

    private YggdrasilServerProbe() {
    }

    public record Probed(String displayName, AuthServerMetadata metadata, OAuthEndpoints oauth) {
    }

    public static Probed probe(HttpGateway http, String apiRoot, String locatorHint,
            Map<String, String> knownClientIds) throws IOException {
        JsonObject root = http.get(apiRoot);
        JsonObject meta = root.get("meta") instanceof JsonObject m ? m : null;
        String displayName = meta != null ? str(meta.get("serverName")) : null;
        if (displayName == null)
            displayName = locatorHint;
        return new Probed(displayName, readMetadata(root), oauthFromMeta(http, meta, apiRoot, knownClientIds));
    }

    public static String resolveApiRoot(String locator, HttpGateway http) throws IOException {
        String api = buildUrl(locator.trim());
        int redirects = 0;
        while (true) {
            if (redirects > MAX_API_LOCATION_HOPS)
                throw new IOException("Too many redirects (" + redirects
                        + ") while resolving API location, last URL: " + api);
            Map<String, List<String>> headers;
            try {
                headers = http.head(api);
            } catch (IOException e) {
                throw new IOException("Failed to resolve API location: " + api, e);
            }
            List<String> apiLocations = NetworkUtils.getHeaderIgnoreCase(headers, "X-Authlib-Injector-API-Location");
            List<String> locations = NetworkUtils.getHeaderIgnoreCase(headers, "Location");
            String candidate = null;
            if (apiLocations != null && !apiLocations.isEmpty())
                candidate = apiLocations.get(0);
            else if (locations != null && !locations.isEmpty())
                candidate = locations.get(0);
            if (candidate == null) {
                if (redirects == 0) {
                    URI baseUri = URI.create(api);
                    return baseUri.getScheme() + "://" + baseUri.getAuthority() + "/api/yggdrasil";
                }
                return api;
            }
            String next = NetworkUtils.resolveLocation(api, candidate);
            if (next.equals(api))
                return api;
            redirects++;
            api = next;
        }
    }

    static String buildUrl(String server) {
        try {
            URI u = URI.create(server);
            if (u.getScheme() != null)
                return server;
            return "https://" + server;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid server URL: " + server, e);
        }
    }

    static AuthServerMetadata readMetadata(JsonObject root) throws IOException {
        List<PublicKey> keys;
        JsonElement keyElement = root.get("signaturePublickey");
        if (keyElement instanceof JsonPrimitive jp && jp.isString()) {
            try {
                keys = List.of(parseSignaturePublicKey(jp.getAsString()));
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new IOException("Invalid Yggdrasil public key!", e);
            }
        } else {
            throw new IOException("Invalid Yggdrasil public key!");
        }
        List<String> skinDomains = new ArrayList<>();
        if (root.get("skinDomains") instanceof JsonArray ja) {
            for (JsonElement je : ja) {
                if (je instanceof JsonPrimitive domain && domain.isString())
                    skinDomains.add(domain.getAsString());
                else
                    throw new IOException("Invalid Yggdrasil skinDomains!");
            }
        } else {
            throw new IOException("Invalid Yggdrasil skinDomains!");
        }
        return new AuthServerMetadata(keys, keys, List.copyOf(skinDomains));
    }

    static OAuthEndpoints oauthFromMeta(HttpGateway http, JsonObject meta, String apiRoot,
            Map<String, String> knownClientIds) throws IOException {
        if (meta == null)
            return null;
        String openidUrl = str(meta.get("feature.openid_configuration_url"));
        if (openidUrl == null)
            return null;
        String host = null;
        try {
            host = URI.create(apiRoot).getHost();
        } catch (IllegalArgumentException ignored) {
        }
        // clientId 只有两个来源：内置 host 表 > YGGC（Yggdrasil Connect）openid 配置里的 shared_client_id。
        // 两处都取不到就判定该服务器不支持 OAuth（退回密码登录），不再凭端点存在瞎猜。
        String clientId = host == null || knownClientIds == null ? null : knownClientIds.get(host);
        JsonObject config = http.get(openidUrl);
        String device = str(config.get("device_authorization_endpoint"));
        String token = str(config.get("token_endpoint"));
        String userinfo = str(config.get("userinfo_endpoint"));
        if (clientId == null)
            clientId = sharedClientId(config);
        if (clientId == null)
            return null;
        return new OAuthEndpoints(clientId, token, device, userinfo);
    }

    static PublicKey parseSignaturePublicKey(String pem)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        pem = pem.replace("\n", "").replace("\r", "");
        String header = "-----BEGIN PUBLIC KEY-----";
        String end = "-----END PUBLIC KEY-----";
        if (!pem.startsWith(header) || !pem.endsWith(end))
            throw new IOException("Bad key format");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(pem.substring(header.length(), pem.length() - end.length()))));
    }

    private static String str(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    /** 读 YGGC openid 配置里的 shared_client_id（规范把它放这里，根 meta 中不会有）：字符串与数字都要认。 */
    static String sharedClientId(JsonObject json) {
        JsonElement e = json == null ? null : json.get("shared_client_id");
        return e instanceof JsonPrimitive p && (p.isString() || p.isNumber()) ? p.getAsString() : null;
    }
}
