package top.syshub.accountsx.common.ui.list;

import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.ui.AvatarHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认的 AccountListModel 实现（P5.2）。
 *
 * <p>把原来散落在各 MC 版本列表控件里的登录调度、排序与删除规则收敛到一处：
 * 适配器只需询问 {@link #canMoveUp}/{@link #canMoveDown}/{@link #isRemovable}，
 * 并把点击交给 {@link #onMove}/{@link #onRemove}/{@link #onSelect}。</p>
 */
public class DefaultAccountListModel implements AccountListModel {
    /** 前两位固定：0 是环境账号，1 是首个存储账号，两者都不参与排序。 */
    private static final int FIRST_MOVABLE = 2;

    @Override
    public List<AccountRow> rows() {
        List<AccountRow> result = new ArrayList<>();
        for (BaseAccount account : AccountManager.getAccountsView()) {
            result.add(new AccountRow(
                    account,
                    account.getAccountStorage().getPlayerName(),
                    account.getAccountStorage().getPlayerName(),
                    account.getAccountType().name(),
                    account.getAccountStorage().getState().name(),
                    new AvatarHandle("", 0L),
                    isRemovable(account),
                    canMoveUp(account) || canMoveDown(account)
            ));
        }
        return result;
    }

    @Override
    public void onSelect(BaseAccount account, Runnable onSwitched) {
        AccountManager.selectAccount(account, onSwitched);
    }

    @Override
    public void onMove(BaseAccount account, int delta) {
        int index = indexOf(account);
        if (index < 0) {
            return;
        }

        AccountManager.moveAccount(account, index + delta);
    }

    @Override
    public void onRemove(BaseAccount account) {
        AccountManager.dropAccount(account);
    }

    @Override
    public boolean isRemovable(BaseAccount account) {
        return account.getAccountType() != AccountType.ENV_DEFAULT;
    }

    @Override
    public boolean canMoveUp(BaseAccount account) {
        return indexOf(account) >= FIRST_MOVABLE;
    }

    @Override
    public boolean canMoveDown(BaseAccount account) {
        int index = indexOf(account);
        return index >= 0 && index < AccountManager.getAccountsView().size() - 1;
    }

    @Override
    public boolean isBusy() {
        return TaskScheduler.isRunning();
    }

    private static int indexOf(BaseAccount account) {
        return AccountManager.getAccountsView().indexOf(account);
    }
}
