package top.syshub.accountsx.common.ui.list;

import java.util.List;

/**
 * 账号列表的通用视图模型（P3.2）。
 *
 * <p>common 负责分组、排序与编排；适配器只负责绘制与命中区域。</p>
 */
public interface AccountListModel {
    List<AccountRow> rows();

    void onSelect(String accountId);

    void onMove(String accountId, int delta);

    void onRemove(String accountId);

    boolean isBusy();
}
