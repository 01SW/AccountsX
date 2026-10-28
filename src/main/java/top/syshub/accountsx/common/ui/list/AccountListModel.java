package top.syshub.accountsx.common.ui.list;

import top.syshub.accountsx.common.accounts.BaseAccount;

import java.util.List;

/**
 * 账号列表的通用视图模型（P3.2 / P5.2）。
 *
 * <p>common 负责分组、排序、登录调度与删除；适配器只负责绘制与命中区域，
 * 从而让各 MC 版本的列表控件只保留渲染代码，不再各自复制一份业务规则。</p>
 */
public interface AccountListModel {
    List<AccountRow> rows();

    /**
     * 切换当前账号。
     *
     * <p>登录在后台线程完成，会话切换与 {@code onSwitched} 都在客户端线程上执行；
     * 已经是当前账号时直接回调，不重复登录。</p>
     */
    void onSelect(BaseAccount account, Runnable onSwitched);

    /** 把账号上移（{@code delta = -1}）或下移（{@code delta = 1}）。 */
    void onMove(BaseAccount account, int delta);

    /** 删除账号。 */
    void onRemove(BaseAccount account);

    /** 该账号能否被删除（环境账号由启动器提供，不可删）。 */
    boolean isRemovable(BaseAccount account);

    /** 该账号能否上移。 */
    boolean canMoveUp(BaseAccount account);

    /** 该账号能否下移。 */
    boolean canMoveDown(BaseAccount account);

    /**
     * 是否允许调整账号顺序。
     *
     * <p>只有「全部账户」视图允许：筛选视图下移动的只是子集，
     * 会打乱用户预期的持久化顺序。</p>
     */
    void setReorderable(boolean reorderable);

    boolean isBusy();
}
