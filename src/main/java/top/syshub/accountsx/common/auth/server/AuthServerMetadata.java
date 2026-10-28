package top.syshub.accountsx.common.auth.server;

import java.security.PublicKey;
import java.util.List;
import java.util.Objects;

/**
 * 认证服务器元数据。
 *
 * @param profileKeySupported 服务端是否声明支持玩家档案密钥对（{@code meta.feature.enable_profile_key}）。
 *                            支持时由服务端签名客户端提供的公钥，账号必须使用真实密钥对；
 *                            不支持时才由客户端兜底伪造，否则强制 profile key 的服务器会拒绝
 *                            「无效的玩家档案公钥签名」
 */
public record AuthServerMetadata(List<PublicKey> profilePropertyKeys, List<PublicKey> playerCertificateKeys, List<String> skinDomains, boolean profileKeySupported) {
    public AuthServerMetadata {
        Objects.requireNonNull(profilePropertyKeys, "profilePropertyKeys");
        Objects.requireNonNull(playerCertificateKeys, "playerCertificateKeys");
        Objects.requireNonNull(skinDomains, "skinDomains");
    }

    /** 兼容旧构造点：未声明能力时按「不支持档案密钥对」处理。 */
    public AuthServerMetadata(List<PublicKey> profilePropertyKeys, List<PublicKey> playerCertificateKeys, List<String> skinDomains) {
        this(profilePropertyKeys, playerCertificateKeys, skinDomains, false);
    }
}
