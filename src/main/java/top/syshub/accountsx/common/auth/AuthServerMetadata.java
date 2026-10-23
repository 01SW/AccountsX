package top.syshub.accountsx.common.auth;

import java.security.PublicKey;
import java.util.List;
import java.util.Objects;

public record AuthServerMetadata(List<PublicKey> profilePropertyKeys, List<PublicKey> playerCertificateKeys, List<String> skinDomains) {
    public AuthServerMetadata {
        Objects.requireNonNull(profilePropertyKeys, "profilePropertyKeys");
        Objects.requireNonNull(playerCertificateKeys, "playerCertificateKeys");
        Objects.requireNonNull(skinDomains, "skinDomains");
    }
}