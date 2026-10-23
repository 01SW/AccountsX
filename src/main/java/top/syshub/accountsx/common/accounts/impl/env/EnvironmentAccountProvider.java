package top.syshub.accountsx.common.accounts.impl.env;

import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.ui.Memory;
import top.syshub.accountsx.common.ui.form.FormAction;
import top.syshub.accountsx.common.ui.form.FormField;
import top.syshub.accountsx.common.ui.form.FormSpec;
import top.syshub.accountsx.common.ui.form.ValidationOutcome;
import java.util.List;

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
    public FormSpec form() {
        return new FormSpec("accountsx.account.general.login", List.of(), List.of(
                new FormAction("close", "accountsx.general.action.close", false)));
    }

    @Override
    public ValidationOutcome validate(Memory memory) {
        throw new UnsupportedOperationException();
    }

    @Override
    public EnvironmentAccount login(Memory memory) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void refresh(EnvironmentAccount account) {
    }
}
