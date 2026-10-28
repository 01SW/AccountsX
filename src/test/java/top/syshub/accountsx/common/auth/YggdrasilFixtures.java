package top.syshub.accountsx.common.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** 测试夹具：假 Yggdrasil 元数据响应 + 合法 RSA 公钥 PEM。token 一律 fake- 前缀（AGENTS.md）。 */
final class YggdrasilFixtures {
    private YggdrasilFixtures() {
    }

    private static String cachedPem;

    static String rsaPublicKeyPem() throws IOException {
        if (cachedPem != null)
            return cachedPem;
        try {
            var pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            cachedPem = "-----BEGIN PUBLIC KEY-----"
                    + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                    + "-----END PUBLIC KEY-----";
            return cachedPem;
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** GET <apiRoot> 的标准响应：serverName / 公钥 / skinDomains / openid 配置。 */
    static JsonObject rootMeta(String apiRoot) throws IOException {
        JsonObject meta = new JsonObject();
        meta.addProperty("serverName", "FakeInjector");
        meta.addProperty("feature.openid_configuration_url", apiRoot + "/.well-known/openid");

        JsonObject root = new JsonObject();
        root.addProperty("signaturePublickey", rsaPublicKeyPem());
        JsonArray domains = new JsonArray();
        domains.add("skin.example.invalid");
        root.add("skinDomains", domains);
        root.add("meta", meta);
        return root;
    }

    static JsonObject openidConfig(String apiRoot) {
        JsonObject config = new JsonObject();
        config.addProperty("device_authorization_endpoint", apiRoot + "/oauth/device");
        config.addProperty("token_endpoint", apiRoot + "/oauth/token");
        config.addProperty("userinfo_endpoint", apiRoot + "/oauth/userinfo");
        config.addProperty("shared_client_id", "fake-shared-client");
        // id_token 验签要用：issuer 校验 iss，jwks_uri 取签名公钥
        config.addProperty("issuer", apiRoot);
        config.addProperty("jwks_uri", apiRoot + "/oauth/jwks");
        return config;
    }

    static JsonObject profile(String name, String uuid32) {
        JsonObject selected = new JsonObject();
        selected.addProperty("name", name);
        selected.addProperty("id", uuid32);
        JsonObject json = new JsonObject();
        json.addProperty("accessToken", "fake-access");
        json.add("selectedProfile", selected);
        return json;
    }
}
