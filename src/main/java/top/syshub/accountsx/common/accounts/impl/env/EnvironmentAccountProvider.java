package top.syshub.accountsx.common.accounts.impl.env;

import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.net.HttpGateway;

import java.util.Objects;

public final class EnvironmentAccountProvider implements AccountProvider<EnvironmentAccount> {
    // 环境账号不发起网络请求；构造器接收网关仅为统一所有 provider 的构造形态（P1.3）。
    public EnvironmentAccountProvider(HttpGateway http) {
        Objects.requireNonNull(http, "http gateway");
    }

    @Override
    public AccountContext createAccountContext(EnvironmentAccount account) {
        return null;
    }

    @Override
    public void refresh(EnvironmentAccount account) {
    }
}
