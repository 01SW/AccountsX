package top.syshub.accountsx.common.auth.server;

import java.net.URI;

/**
 * apiRoot 作为查表键时的规范化口径：scheme 与 host 小写、去掉尾部斜杠，路径、端口与 query 照原样保留。
 *
 * <p>所以 {@code HTTPS://Skin.JSUMC.fun/api/yggdrasil/} 与 {@code https://skin.jsumc.fun/api/yggdrasil}
 * 是同一个键。规范化只做这一件事，client_id 表本身是纯数据。</p>
 */
public final class ApiRootKey {

    public static String of(String apiRoot) {
        if (apiRoot == null)
            return null;
        String trimmed = trimTrailingSlashes(apiRoot.trim());
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null)
                return trimmed;
            StringBuilder key = new StringBuilder(scheme.toLowerCase()).append("://").append(host.toLowerCase());
            if (uri.getPort() != -1)
                key.append(':').append(uri.getPort());
            if (uri.getRawPath() != null)
                key.append(uri.getRawPath());
            if (uri.getRawQuery() != null)
                key.append('?').append(uri.getRawQuery());
            return key.toString();
        } catch (IllegalArgumentException e) {
            return trimmed;
        }
    }

    private static String trimTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private ApiRootKey() {
    }
}
