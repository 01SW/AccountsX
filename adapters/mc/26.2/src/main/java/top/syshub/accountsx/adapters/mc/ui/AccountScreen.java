package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.adapters.mc.ui.impl.UIScreenImpl;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.auth.AuthServer;
import top.syshub.accountsx.common.auth.AuthServerKind;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.task.TaskScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 账号列表界面。
 *
 * <p>左侧是单选的账户筛选（全部 / 微软 / 各外置认证服务器 / 离线），右侧只显示被筛选出的账号；
 * 「添加认证服务器」固定在左下角，「添加账户」固定在右侧列表底部（「全部账户」视图下不显示，
 * 因为此时没有确定的账号类型可加）。</p>
 */
public class AccountScreen extends Screen {
    private static final int PAD = 8;
    private static final int SIDEBAR_W = 150;
    private static final int BTN_H = 20;
    private static final int BTN_GAP = 4;
    private static final int ENTRY_H = 36;
    private static final int LIST_TOP = 48;

    private final Component WORKING = Component.translatable("accountsx.account.general.operating");
    private final Component ACCOUNT_LIST = Component.translatable("accountsx.account.general.account_list");

    private final Screen parent;
    private AccountListWidget accountListWidget;

    private final List<Filter> filters = new ArrayList<>();
    private int selectedFilter;

    public AccountScreen(Screen parent) {
        super(Component.translatable("accountsx.account.general.account_list"));
        this.parent = parent;
    }

    public void onClose() {
        assert this.minecraft != null;

        this.minecraft.setScreenAndShow(this.parent);
    }

    public void syncAccounts() {
        if (this.accountListWidget != null) {
            this.accountListWidget.syncAccounts();
        }
    }

    @Override
    protected void init() {
        super.init();
        this.rebuildFilters();
        this.updateWidgets();
    }

    private void rebuildFilters() {
        this.filters.clear();
        this.filters.add(new Filter(Component.translatable("accountsx.account.filter.all"),
                account -> true, null));
        this.filters.add(new Filter(Component.translatable("accountsx.account.filter.microsoft"),
                account -> account.getAccountType() == AccountType.MICROSOFT, AccountType.MICROSOFT));
        for (AuthServer server : AccountManager.authServers().servers()) {
            // 统一通行证暂不进入筛选（该类型后续会移除）；只有外置认证服务器有筛选价值。
            if (server.kind() != AuthServerKind.YGGDRASIL) {
                continue;
            }
            String apiRoot = server.apiRoot();
            if (apiRoot == null) {
                continue;
            }
            String displayName = server.displayName();
            Component label = Component.literal(displayName == null || displayName.isBlank() ? apiRoot : displayName);
            this.filters.add(new Filter(label, account -> isOnServer(account, apiRoot), AccountType.AUTHLIB_INJECTOR));
        }
        this.filters.add(new Filter(Component.translatable("accountsx.account.filter.offline"),
                account -> account.getAccountType() == AccountType.OFFLINE, AccountType.OFFLINE));
        if (this.selectedFilter >= this.filters.size()) {
            this.selectedFilter = 0;
        }
    }

    private void updateWidgets() {
        this.clearWidgets();

        int x = PAD;
        int buttonWidth = SIDEBAR_W - PAD * 2;
        int y = PAD + 12;

        this.addField(new ButtonWidget(x, y, buttonWidth, BTN_H,
                Component.translatable("accountsx.general.action.close"),
                button -> this.onClose()));

        y += BTN_H + 16;
        for (int i = 0; i < this.filters.size(); i++) {
            final int index = i;
            this.addField(new ButtonWidget(x, y, buttonWidth, BTN_H, this.filters.get(i).label(),
                    button -> this.selectFilter(index)));
            y += BTN_H + BTN_GAP;
        }

        this.addField(new ButtonWidget(x, this.height - PAD - BTN_H, buttonWidth, BTN_H,
                Component.translatable("accountsx.account.general.add_auth_server"),
                button -> {
                    assert this.minecraft != null;

                    this.minecraft.setScreenAndShow(new ServerScreen(this));
                }));

        int listLeft = SIDEBAR_W + PAD;
        int listRight = this.width - PAD;
        int listBottom = this.height - PAD - (this.selectedFilter == 0 ? 0 : BTN_H + BTN_GAP);

        if (this.accountListWidget == null) {
            this.accountListWidget = new AccountListWidget(this.minecraft, listLeft, listRight, LIST_TOP, listBottom, ENTRY_H);
        } else {
            this.accountListWidget.updateSize(listLeft, listRight, LIST_TOP, listBottom);
        }
        this.accountListWidget.setFilter(this.filters.get(this.selectedFilter).predicate());
        this.addWidget(this.accountListWidget);

        if (this.selectedFilter != 0) {
            AccountType addType = this.filters.get(this.selectedFilter).addType();
            this.addField(new ButtonWidget(listLeft, listBottom + BTN_GAP, listRight - listLeft, BTN_H,
                    Component.translatable("accountsx.account.general.add_account"),
                    button -> {
                        // addType 在「全部账户」视图下为 null，而该按钮自身也只在非全部视图出现。
                        assert this.minecraft != null && addType != null;

                        UIScreenImpl.login(this.minecraft, this, addType.getAccountProvider());
                    }));
        }
    }

    private void selectFilter(int index) {
        if (index == this.selectedFilter) {
            return;
        }

        this.selectedFilter = index;
        this.updateWidgets();
    }

    private static boolean isOnServer(BaseAccount account, String apiRoot) {
        if (account.getAccountType() != AccountType.AUTHLIB_INJECTOR || !(account instanceof AbstractInjectorAccount injector)) {
            return false;
        }

        return apiRoot.equals(injector.getServer());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // 版本间 renderBackground 的参数不同，这里统一自绘一层压暗，避免依赖具体签名。
        context.fill(0, 0, this.width, this.height, 0x99000000);
        context.fill(0, 0, SIDEBAR_W, this.height, 0x66000000);
        context.fill(SIDEBAR_W, 0, SIDEBAR_W + 2, this.height, 0x40FFFFFF);

        int selectedY = PAD + 12 + BTN_H + 16 + this.selectedFilter * (BTN_H + BTN_GAP);
        context.fill(PAD - 3, selectedY - 2, SIDEBAR_W - PAD + 3, selectedY + BTN_H + 2, 0x5090A0FF);

        super.extractRenderState(context, mouseX, mouseY, delta);
        this.accountListWidget.extractRenderState(context, mouseX, mouseY, delta);

        int centerX = (SIDEBAR_W + PAD + this.width - PAD) / 2;
        context.centeredText(this.font, TaskScheduler.isRunning() ? WORKING : ACCOUNT_LIST, centerX, PAD, 0xFFFFFF);
        context.centeredText(this.font, I18N.TRANSLATOR.translate(AccountManager.getCurrentAccount()), centerX, PAD + 12, 0xA0A0A0);
    }

    public void addField(AbstractWidget drawable) {
        this.addRenderableOnly(drawable);
        this.addWidget(drawable);
    }

    private record Filter(Component label, Predicate<BaseAccount> predicate, AccountType addType) {
    }
}
