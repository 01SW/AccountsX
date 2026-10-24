package top.syshub.accountsx.common.ui.list;

import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.ui.AvatarHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认的 AccountListModel 实现（P5.2）。
 *
 * <p>包装 AccountManager 的账号列表，提供视图模型层。</p>
 */
public class DefaultAccountListModel implements AccountListModel {
    @Override
    public List<AccountRow> rows() {
        List<AccountRow> result = new ArrayList<>();
        for (BaseAccount account : AccountManager.getAccountsView()) {
            result.add(new AccountRow(
                    account.getAccountStorage().getPlayerName(),
                    account.getAccountStorage().getPlayerName(),
                    account.getAccountType().name(),
                    account.getAccountStorage().getState().name(),
                    new AvatarHandle("", 0L),
                    account.getAccountType() != top.syshub.accountsx.common.accounts.model.AccountType.ENV_DEFAULT,
                    true
            ));
        }
        return result;
    }

    @Override
    public void onSelect(String accountId) {
        // 由适配器处理选择逻辑
    }

    @Override
    public void onMove(String accountId, int delta) {
        // 由适配器处理移动逻辑
    }

    @Override
    public void onRemove(String accountId) {
        // 由适配器处理删除逻辑
    }

    @Override
    public boolean isBusy() {
        return false;
    }
}
