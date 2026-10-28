package top.syshub.accountsx.common.auth.server;

/**
 * 服务器地址的明文传输判定。
 *
 * <p>启动器技术规范要求：地址缺省协议时补 {@code https}，且「即使无法通过 HTTPS 连接，也不得降级到
 * 明文 HTTP」。用户自己写出的 {@code http://} 不属于「降级」，本项目不替用户改写，由界面醒目警告
 * ——密码、accessToken、clientToken 都会明文过网。</p>
 */
public final class ServerAddressSecurity {
    private static final String PLAINTEXT_SCHEME = "http://";

    private ServerAddressSecurity() {
    }

    /** 地址是否显式使用明文 HTTP；null、空白与 https 都算安全（凑不出来就走探测失败）。 */
    public static boolean isPlaintext(String address) {
        if (address == null) {
            return false;
        }

        String trimmed = address.trim();
        if (trimmed.length() < PLAINTEXT_SCHEME.length()) {
            return false;
        }

        return trimmed.regionMatches(true, 0, PLAINTEXT_SCHEME, 0, PLAINTEXT_SCHEME.length());
    }
}
