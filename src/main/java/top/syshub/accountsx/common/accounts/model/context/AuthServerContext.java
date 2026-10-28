package top.syshub.accountsx.common.accounts.model.context;

public record AuthServerContext(
        String authURL, String accountURL, String sessionURL, String serviceURL,
        String name,
        boolean profileKeySupported
) {
    /** 兼容不涉及档案密钥对的构造点（Mojang 官方环境）。 */
    public AuthServerContext(String authURL, String accountURL, String sessionURL, String serviceURL, String name) {
        this(authURL, accountURL, sessionURL, serviceURL, name, false);
    }
}
