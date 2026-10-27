package top.syshub.accountsx.common.auth;

import java.net.URI;
import java.util.Map;

/**
 * 内置 OAuth client_id 表，键是完整 apiRoot（口径见 {@link #key}）。
 *
 * <p>探测顺序是「这张表 → YGGC（Yggdrasil Connect）openid 配置里的 shared_client_id → 视为不支持 OAuth」：
 * 表里的实例是我们已经核过 client_id 的，优先用它，就不依赖服务器是否发布 shared_client_id。</p>
 *
 * <p>键用 apiRoot 而不是 host：client_id 是按 API 根注册的，同一域名下可以挂多个 API 根，
 * 也可能哪天挪到别的路径——按主机名匹配会把这些情况搅在一起。</p>
 */
public final class OAuthClientIds {
    private static final Map<String, String> BY_API_ROOT = Map.of(
            "https://skin.jsumc.fun/api/yggdrasil", "3",
            "https://skin.mualliance.ltd/api/yggdrasil", "28",
            "https://littleskin.cn/api/yggdrasil", "1214",
            "https://mcskin.ecustvr.top/api/yggdrasil", "6");

    /** 默认表：启动预热的重新探测与「添加认证服务器」都用它。 */
    public static Map<String, String> defaults() {
        return BY_API_ROOT;
    }

    /**
     * 表键口径：scheme 与 host 小写、去掉尾部斜杠，路径与端口照原样保留。
     * 所以 {@code HTTPS://Skin.JSUMC.fun/api/yggdrasil/} 与 {@code https://skin.jsumc.fun/api/yggdrasil} 是同一个键。
     */
    static String key(String apiRoot) {
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

    private OAuthClientIds() {
    }
}
