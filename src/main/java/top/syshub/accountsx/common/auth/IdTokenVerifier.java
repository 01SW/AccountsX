package top.syshub.accountsx.common.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import top.syshub.accountsx.common.net.HttpGateway;

/**
 * id_token（OIDC JWT）校验：签名 + {@code iss} / {@code aud} / {@code iat} / {@code exp}。
 *
 * <p>只解 Base64 读 payload 等于「谁塞给我一张纸我就信」——任何人构造一个
 * {@code {"alg":"none"}} 的 JWT 都能冒充任意角色。规范要求校验失败即拒绝使用，
 * 因此这里宁可让登录失败，也不接受未经验证的令牌。</p>
 *
 * <p>签名密钥来自服务端 openid 配置的 {@code jwks_uri}（由探测写进
 * {@link OAuthEndpoints#jwksUri()}），按 uri 缓存十分钟：刷新令牌时没必要每次重取。</p>
 */
public final class IdTokenVerifier {

    /** 允许的时钟偏差（秒）：iat 略晚于本机时间、exp 刚过都可能是对时误差，不因此判定伪造。 */
    private static final long CLOCK_SKEW_SECONDS = 60;

    private static final long KEY_TTL_MILLIS = 10 * 60 * 1000L;

    private static final String RS256 = "RS256";

    private static final Map<String, CachedKeys> KEY_CACHE = new ConcurrentHashMap<>();

    private record CachedKeys(List<PublicKey> keys, long expiresAt) {
        boolean fresh() {
            return System.currentTimeMillis() < expiresAt;
        }
    }

    private IdTokenVerifier() {
    }

    /**
     * 校验并返回 id_token 的声明集（payload）。
     *
     * @param server 认证服务器；其 {@link OAuthEndpoints} 必须带 issuer（缺 jwks_uri 时按 OIDC 发现的
     *               标准地址 {@code <issuer>/.well-known/openid-configuration} 现取）
     */
    public static JsonObject verify(HttpGateway http, AuthServer server, String idToken) throws IOException {
        OAuthEndpoints oauth = server == null ? null : server.oauth();
        String issuer = oauth == null ? null : oauth.issuer();
        if (issuer == null || issuer.isBlank())
            throw new IOException("This server's OAuth configuration declares no issuer; cannot verify the id token.");
        String clientId = oauth.clientId();
        if (clientId == null || clientId.isBlank())
            throw new IOException("This server's OAuth configuration declares no client id; cannot verify the id token.");

        String jwksUri = oauth.jwksUri();
        if (jwksUri == null || jwksUri.isBlank())
            jwksUri = discoverJwksUri(http, issuer);
        return verify(idToken, issuer, clientId, keys(http, jwksUri));
    }

    /** 纯校验（不发网络）：签名、签发者、受众、签发/过期时间。测试与离线调用用这个入口。 */
    public static JsonObject verify(String idToken, String expectedIssuer, String expectedAudience,
            List<PublicKey> keys) throws IOException {
        if (idToken == null || idToken.isBlank())
            throw new IOException("Invalid id token.");
        String[] parts = idToken.split("\\.");
        if (parts.length != 3)
            throw new IOException("Invalid id token.");
        JsonObject header = decodePart(parts[0]);
        String alg = str(header.get("alg"));
        if (alg == null || !RS256.equals(alg))
            throw new IOException("Unsupported id token algorithm: " + alg);
        JsonObject claims = decodePart(parts[1]);
        verifySignature(parts[0] + "." + parts[1], parts[2], str(header.get("kid")), keys);

        String iss = str(claims.get("iss"));
        if (iss == null || !sameIssuer(iss, expectedIssuer))
            throw new IOException("id token was issued by " + iss + ", expected " + expectedIssuer);
        if (!audienceContains(claims.get("aud"), expectedAudience))
            throw new IOException("id token is not addressed to this client");
        String subject = str(claims.get("sub"));
        if (subject == null || subject.isBlank())
            throw new IOException("id token has no subject");
        long now = System.currentTimeMillis() / 1000L;
        JsonElement expElement = claims.get("exp");
        if (!(expElement instanceof JsonPrimitive expPrimitive) || !expPrimitive.isNumber())
            throw new IOException("id token has no expiry");
        if (expPrimitive.getAsLong() + CLOCK_SKEW_SECONDS < now)
            throw new IOException("id token has expired");
        JsonElement iatElement = claims.get("iat");
        if (iatElement instanceof JsonPrimitive iatPrimitive && iatPrimitive.isNumber()
                && iatPrimitive.getAsLong() - CLOCK_SKEW_SECONDS > now)
            throw new IOException("id token was issued in the future");
        return claims;
    }

    private static void verifySignature(String signingInput, String signature, String kid, List<PublicKey> keys)
            throws IOException {
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid id token signature encoding", e);
        }
        // kid 只是提示：服务端轮换密钥后可能给出未知 kid，能验通就说明密钥是对的。
        for (PublicKey key : keys) {
            try {
                Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(key);
                verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
                if (verifier.verify(signatureBytes))
                    return;
            } catch (GeneralSecurityException e) {
                throw new IOException("Cannot verify id token signature", e);
            }
        }
        throw new IOException("id token signature does not match any published key"
                + (kid == null ? "" : " (kid=" + kid + ")"));
    }

    /** 取（或复用缓存的）签名公钥；缓存过期后重新拉取，失败不写缓存。 */
    static List<PublicKey> keys(HttpGateway http, String jwksUri) throws IOException {
        CachedKeys cached = KEY_CACHE.get(jwksUri);
        if (cached != null && cached.fresh())
            return cached.keys();
        JsonObject document = http.get(jwksUri);
        List<PublicKey> keys = parseJwks(document, jwksUri);
        KEY_CACHE.put(jwksUri, new CachedKeys(keys, System.currentTimeMillis() + KEY_TTL_MILLIS));
        return keys;
    }

    /** 解析 JWKS 文档里可用的 RSA 公钥（其余 kty 直接忽略：我们只支持 RS256）。 */
    static List<PublicKey> parseJwks(JsonObject document, String source) throws IOException {
        JsonElement keys = document == null ? null : document.get("keys");
        if (!(keys instanceof JsonArray array))
            throw new IOException("JWKS document has no keys array: " + source);
        List<PublicKey> out = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!(element instanceof JsonObject jwk) || !"RSA".equals(str(jwk.get("kty"))))
                continue;
            try {
                out.add(parseJwk(jwk));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new IOException("Invalid RSA key in JWKS document: " + source, e);
            }
        }
        if (out.isEmpty())
            throw new IOException("JWKS document publishes no RSA key: " + source);
        return List.copyOf(out);
    }

    static PublicKey parseJwk(JsonObject jwk) throws GeneralSecurityException, IllegalArgumentException {
        String modulus = str(jwk.get("n"));
        String exponent = str(jwk.get("e"));
        if (modulus == null || exponent == null)
            throw new IllegalArgumentException("JWK without n/e");
        Base64.Decoder decoder = Base64.getUrlDecoder();
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, decoder.decode(modulus)), new BigInteger(1, decoder.decode(exponent))));
    }

    /** 没有 jwks_uri 时按 OIDC 发现标准地址现取（老配置只存了 token 端点，升级后第一次刷新走这里）。 */
    private static String discoverJwksUri(HttpGateway http, String issuer) throws IOException {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        JsonObject config = http.get(base + "/.well-known/openid-configuration");
        String jwks = str(config == null ? null : config.get("jwks_uri"));
        if (jwks == null || jwks.isBlank())
            throw new IOException("OpenID configuration of " + issuer + " publishes no jwks_uri; "
                    + "cannot verify the id token.");
        return jwks;
    }

    private static boolean sameIssuer(String actual, String expected) {
        // 服务端常在 issuer 末尾多写一个 /，两者不等值但同一个发行者。
        return stripSlash(actual).equals(stripSlash(expected));
    }

    private static String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean audienceContains(JsonElement aud, String expected) {
        if (aud instanceof JsonPrimitive primitive && primitive.isString())
            return expected.equals(primitive.getAsString());
        if (aud instanceof JsonArray array) {
            for (JsonElement element : array) {
                if (element instanceof JsonPrimitive primitive && primitive.isString()
                        && expected.equals(primitive.getAsString()))
                    return true;
            }
        }
        return false;
    }

    private static JsonObject decodePart(String part) throws IOException {
        try {
            String json = new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject())
                throw new IOException("Invalid id token segment.");
            return parsed.getAsJsonObject();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IOException("Invalid id token encoding", e);
        }
    }

    private static String str(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }
}
