package top.syshub.accountsx.common.auth;

import java.util.Objects;

/**
 * 当前会话的注册表单例持有者。
 *
 * <p>{@code AccountType} 在类加载期就 new 好了 provider 单例，注册表要等
 * {@code AccountRepository.initialize} 才存在，两者之间唯一的桥就是这个 volatile 槽。
 * 未安装时返回一个不落盘的游离注册表（仅兜底：生产路径 initialize 先于任何登录）。</p>
 *
 * <p>P3.3 把服务器模型接进 UI SPI 后，本类与游离兜底一起删除。</p>
 */
public final class AuthServers {
    private static volatile AuthServerRegistry registry;

    private AuthServers() {
    }

    public static void install(AuthServerRegistry next) {
        registry = Objects.requireNonNull(next, "next");
    }

    /** 测试用：清空槽位（生产不调用）。 */
    public static void resetForTest() {
        registry = null;
    }

    public static AuthServerRegistry current() {
        AuthServerRegistry r = registry;
        if (r != null)
            return r;
        return new AuthServerRegistry(top.syshub.accountsx.common.net.JdkHttpGateway.INSTANCE);
    }
}
