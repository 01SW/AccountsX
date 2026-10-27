package top.syshub.accountsx.common.accounts;

import top.syshub.accountsx.common.accounts.model.context.AccountContext;

import java.io.IOException;

/**
 * 账号类型的运行时能力：建 authlib 上下文、刷新资料。
 *
 * <p>建号入口不在这里——每种类型的登录流程差别太大（密码、浏览器设备码、OAuth 轮询、纯本地），
 * 各自的 provider 暴露自己的方法，由专用界面直接调用（外置认证界面调 {@code loginPassword} /
 * {@code loginDeviceCode}，微软界面调 {@code requestDeviceCode} / {@code loginDeviceCode}）。</p>
 */
public interface AccountProvider<T extends BaseAccount> {
    AccountContext createAccountContext(T account) throws IOException;

    void refresh(T account) throws IOException;

    @SuppressWarnings("unchecked")
    static <T extends BaseAccount> AccountProvider<T> getProvider(T account) {
        return (AccountProvider<T>) account.getAccountType().getAccountProvider();
    }
}
