package top.syshub.accountsx.common.auth;

import java.util.Map;

/**
 * 内置 OAuth client_id 表，键是 apiRoot 的 host。
 *
 * <p>探测顺序是「这张表 → YGGC（Yggdrasil Connect）openid 配置里的 shared_client_id → 视为不支持 OAuth」：
 * 表里的实例是我们已经核过 client_id 的，优先用它，就不依赖服务器是否发布 shared_client_id。</p>
 */
public final class OAuthClientIds {
    public static final Map<String, String> BY_HOST = Map.of(
            "skin.jsumc.fun", "3",
            "skin.mualliance.ltd", "28",
            "littleskin.cn", "1214",
            "mcskin.ecustvr.top", "6");

    private OAuthClientIds() {
    }
}

