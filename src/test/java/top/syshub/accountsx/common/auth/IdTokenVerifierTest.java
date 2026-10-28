package top.syshub.accountsx.common.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.net.HttpGateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * id_token 校验：签名、签发者、受众、签发/过期时间。全部用本地密钥对，无真实凭据。
 */
class IdTokenVerifierTest {
    private static final String ISSUER = "https://ygg.invalid/api/yggdrasil";
    private static final String CLIENT = "fake-shared-client";

    private KeyPair keyPair;

    @BeforeEach
    void generateKeys() throws Exception {
        keyPair = KeyPairGenerator.getInstance("RSA").genKeyPair();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private String token(KeyPair signer, String alg, JsonObject claims) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString(("{\"alg\":\"" + alg + "\",\"kid\":\"fake-kid\"}")
                .getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        if (!"RS256".equals(alg))
            return header + "." + payload + ".fakesig";
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signer.getPrivate());
        signature.update((header + "." + payload).getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + enc.encodeToString(signature.sign());
    }

    private JsonObject claims() {
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", ISSUER);
        claims.addProperty("aud", CLIENT);
        claims.addProperty("sub", "fake-user");
        claims.addProperty("iat", now());
        claims.addProperty("exp", now() + 3600);
        JsonArray profiles = new JsonArray();
        JsonObject profile = new JsonObject();
        profile.addProperty("name", "Alex");
        profile.addProperty("id", "069a79f444e94726a5befca90e38aaf5");
        profiles.add(profile);
        claims.add("availableProfiles", profiles);
        return claims;
    }

    private JsonObject verifyValid(String idToken) throws IOException {
        return IdTokenVerifier.verify(idToken, ISSUER, CLIENT, List.of(keyPair.getPublic()));
    }

    @Test
    void validToken_returnsClaims() throws Exception {
        JsonObject claims = verifyValid(token(keyPair, "RS256", claims()));

        assertThat(claims.get("sub").getAsString()).isEqualTo("fake-user");
        assertThat(claims.get("availableProfiles").getAsJsonArray()).hasSize(1);
    }

    @Test
    void tokenSignedByAnotherKey_isRejected() throws Exception {
        KeyPair other = KeyPairGenerator.getInstance("RSA").genKeyPair();

        assertThatThrownBy(() -> verifyValid(token(other, "RS256", claims())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("signature does not match")
                .hasMessageContaining("kid=fake-kid");
    }

    @Test
    void unsignedAlgNone_isRejected() throws Exception {
        // 只解 Base64 读 payload 的实现会把这个当合法令牌：任何人都能伪造任意角色。
        assertThatThrownBy(() -> verifyValid(token(keyPair, "none", claims())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsupported id token algorithm: none");
    }

    @Test
    void wrongIssuer_isRejected() throws Exception {
        JsonObject claims = claims();
        claims.addProperty("iss", "https://evil.invalid/api/yggdrasil");

        assertThatThrownBy(() -> verifyValid(token(keyPair, "RS256", claims)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("was issued by https://evil.invalid/api/yggdrasil");
    }

    @Test
    void trailingSlashOnIssuer_isStillTheSameIssuer() throws Exception {
        JsonObject claims = claims();
        claims.addProperty("iss", ISSUER + "/");

        assertThat(verifyValid(token(keyPair, "RS256", claims)).get("sub").getAsString()).isEqualTo("fake-user");
    }

    @Test
    void wrongAudience_isRejected() throws Exception {
        JsonObject claims = claims();
        claims.addProperty("aud", "someone-elses-client");

        assertThatThrownBy(() -> verifyValid(token(keyPair, "RS256", claims)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not addressed to this client");
    }

    @Test
    void audienceArray_containingClient_isAccepted() throws Exception {
        JsonObject claims = claims();
        JsonArray audiences = new JsonArray();
        audiences.add("someone-elses-client");
        audiences.add(CLIENT);
        claims.add("aud", audiences);

        assertThat(verifyValid(token(keyPair, "RS256", claims)).get("sub").getAsString()).isEqualTo("fake-user");
    }

    @Test
    void expiredToken_isRejected() throws Exception {
        JsonObject claims = claims();
        claims.addProperty("exp", now() - 3600);

        assertThatThrownBy(() -> verifyValid(token(keyPair, "RS256", claims)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("has expired");
    }

    @Test
    void tokenIssuedInTheFuture_isRejected() throws Exception {
        JsonObject claims = claims();
        claims.addProperty("iat", now() + 3600);

        assertThatThrownBy(() -> verifyValid(token(keyPair, "RS256", claims)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("issued in the future");
    }

    @Test
    void tokenWithoutIssuedAt_isRejected() throws Exception {
        JsonObject claims = claims();
        claims.remove("iat");

        assertThatThrownBy(() -> verifyValid(token(keyPair, "RS256", claims)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no issued-at time");
    }

    @Test
    void tokenWithoutExpiry_isRejected() throws Exception {
        JsonObject claims = claims();
        claims.remove("exp");

        assertThatThrownBy(() -> verifyValid(token(keyPair, "RS256", claims)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no expiry");
    }

    @Test
    void jwksDocument_yieldsPublicKeyAndIgnoresOtherKeyTypes() throws IOException {
        JsonObject document = new JsonObject();
        JsonArray keys = new JsonArray();
        JsonObject ec = new JsonObject();
        ec.addProperty("kty", "EC");
        keys.add(ec);
        keys.add(jwk(keyPair.getPublic()));
        document.add("keys", keys);

        List<PublicKey> parsed = IdTokenVerifier.parseJwks(document, "fake-jwks");

        assertThat(parsed).hasSize(1);
    }

    @Test
    void jwksWithoutKeysArray_isRejected() {
        assertThatThrownBy(() -> IdTokenVerifier.parseJwks(new JsonObject(), "fake-jwks"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no keys array");
    }

    @Test
    void keys_areCachedPerJwksUri() throws IOException {
        CountingGateway gateway = new CountingGateway(jwksDocument());

        IdTokenVerifier.keys(gateway, "https://ygg.invalid/fake-jwks-1");
        IdTokenVerifier.keys(gateway, "https://ygg.invalid/fake-jwks-1");

        assertThat(gateway.hits).hasValue(1);
    }

    private JsonObject jwksDocument() {
        JsonObject document = new JsonObject();
        JsonArray keys = new JsonArray();
        keys.add(jwk(keyPair.getPublic()));
        document.add("keys", keys);
        return document;
    }

    private static JsonObject jwk(PublicKey key) {
        RSAPublicKey rsa = (RSAPublicKey) key;
        JsonObject jwk = new JsonObject();
        jwk.addProperty("kty", "RSA");
        jwk.addProperty("kid", "fake-kid");
        jwk.addProperty("n", unsigned(rsa.getModulus()));
        jwk.addProperty("e", unsigned(rsa.getPublicExponent()));
        return jwk;
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0)
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 只回答 JWKS GET 的最小网关，其余方法一律视为误用。 */
    private static final class CountingGateway implements HttpGateway {
        private final JsonObject document;
        private final AtomicInteger hits = new AtomicInteger();

        CountingGateway(JsonObject document) {
            this.document = document;
        }

        @Override
        public JsonObject get(String url) {
            hits.incrementAndGet();
            return document;
        }

        @Override
        public JsonObject get(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] getBinary(String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Headers getHeaders(String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonObject postJson(String url, com.google.gson.JsonElement body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonObject postJson(String url, com.google.gson.JsonElement body, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> form) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> form, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException();
        }
    }
}
