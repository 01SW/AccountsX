package top.syshub.accountsx.common.accounts;

import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.ui.Memory;
import top.syshub.accountsx.common.ui.form.FormSpec;
import top.syshub.accountsx.common.ui.form.ValidationOutcome;

import java.io.IOException;

public interface AccountProvider<T extends BaseAccount> {
    FormSpec form();

    ValidationOutcome validate(Memory memory) throws IllegalArgumentException;

    AccountContext createAccountContext(T account) throws IOException;

    T login(Memory memory) throws IOException;

    void refresh(T account) throws IOException;

    @SuppressWarnings("unchecked")
    static <T extends BaseAccount> AccountProvider<T> getProvider(T account) {
        return (AccountProvider<T>) account.getAccountType().getAccountProvider();
    }
}
