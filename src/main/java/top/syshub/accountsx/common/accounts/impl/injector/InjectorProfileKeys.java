package top.syshub.accountsx.common.accounts.impl.injector;

/**
 * 当前会话是否由「声明支持档案密钥对」的外置认证服务器提供。
 *
 * <p>adapter 的 {@code YggdrasilUserApiServiceMixin} 在 authlib-injector 环境下会伪造一对 RSA
 * 密钥对顶替服务端签名，用来兼容不校验 <em>profile key</em> 的老服务端。但服务端一旦声明
 * {@code meta.feature.enable_profile_key}、并且服务器开着 {@code enforce-secure-profile}，
 * 伪造的签名会被直接拒绝（"无效的玩家档案公钥签名"），必须让 authlib 走真实端点。</p>
 *
 * <p>能力来自登录时那次 {@code /api/yggdrasil} 探测的元数据，因此由
 * {@code AccountManager.loginAccount} 在切换账号时写入；mixin 只读。默认 {@code false}
 * 表示「未知」，此时维持原有伪造行为，避免影响从未探测过的老缓存。</p>
 */
public final class InjectorProfileKeys {
    private static volatile boolean supported;

    private InjectorProfileKeys() {
    }

    /** 登录链路写入：本次会话使用的认证服务器是否支持档案密钥对。 */
    public static void setSupported(boolean value) {
        supported = value;
    }

    /** mixin 读取：支持时必须放弃伪造，交给 authlib 走真实端点。 */
    public static boolean isSupported() {
        return supported;
    }
}
