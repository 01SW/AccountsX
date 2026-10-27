package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.manager.AccountManager;

/**
 * 登录类界面（外置认证 / 微软 / 离线）的公共骨架。
 *
 * <p>三者的差别只有「填什么、点哪个按钮」，页头、状态行、返回按钮以及「关窗即取消」的语义都一样，
 * 所以收敛到这里。子类的在途请求用 {@link #valid(long)} 判断迟到的结果是否还该采用。</p>
 */
abstract class LoginScreen extends Screen {
    protected static final int PAD = 8;
    protected static final int BTN_H = 20;
    protected static final int INPUT_W = 240;
    protected static final int TOP = 96;

    protected final Screen parent;
    private final Component subtitle;

    /** 轮询线程会读这两个字段，写入全在客户端线程，故声明 volatile。 */
    protected volatile boolean closed;
    protected volatile long requestId;

    protected boolean busy;
    protected Component status;

    protected LoginScreen(Screen parent, String titleKey, Component subtitle) {
        super(Component.translatable(titleKey));
        this.parent = parent;
        this.subtitle = subtitle;
    }

    @Override
    public void onClose() {
        // 关闭即取消：轮询线程与在途回调都以此为准。
        this.closed = true;
        Minecraft minecraft = this.minecraft;
        if (minecraft == null) {
            return;
        }
        minecraft.setScreenAndShow(this.parent);
    }

    @Override
    protected void init() {
        super.init();
        this.updateWidgets();
    }

    /** 控件集合整体重建：子类先 clearWidgets()，再按当前状态摆一遍。 */
    protected abstract void updateWidgets();

    protected final void addField(AbstractWidget widget) {
        this.addRenderableOnly(widget);
        this.addWidget(widget);
    }

    /** 结果是否仍属于当前这次操作：关窗或又发起了新操作都会让它失真。 */
    protected final boolean valid(long rid) {
        return !this.closed && rid == this.requestId;
    }

    protected final int contentX() {
        return this.width / 2 - INPUT_W / 2;
    }

    /** 登录成功：入库、让父界面的列表跟上，然后回到父界面。 */
    protected final void finish(BaseAccount account) {
        AccountManager.addAccount(account);
        Screen parentScreen = this.parent;
        if (parentScreen instanceof AccountScreen accountScreen) {
            accountScreen.syncAccounts();
        }
        this.onClose();
    }

    protected static Component failText(Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return Component.translatable("accountsx.account.server.fail", detail);
    }

    /** 页头：压暗背景 + 标题 + 副标题（服务器名 / 账号类型）。 */
    protected final void drawHeader(GuiGraphicsExtractor context) {
        context.fill(0, 0, this.width, this.height, 0x99000000);
        this.center(context, this.title, 40, 0xFFFFFFFF);
        this.center(context, this.subtitle, 56, 0xFFA0A0A0);
    }

    /** 状态行：失败原因、「正在操作」等。 */
    protected final void drawStatus(GuiGraphicsExtractor context) {
        Component text = this.status;
        if (text != null) {
            this.center(context, text, this.height - PAD - BTN_H - 16, 0xFFA0A0A0);
        }
    }

    protected final void center(GuiGraphicsExtractor context, Component text, int y, int color) {
        context.centeredText(this.font, text, this.width / 2, y, color);
    }

    /** 底部「返回」：忙碌时它就是取消入口（onClose 会终止在途请求）。 */
    protected final void addBackButton() {
        this.addField(new ButtonWidget(this.contentX(), this.height - PAD - BTN_H, INPUT_W, BTN_H,
                Component.translatable("accountsx.general.action.close"),
                button -> this.onClose()));
    }

    /**
     * 密码框遮蔽。
     *
     * <p>各版本 EditBox 的格式化 API 不同，而这份代码要同时编译十几个版本，所以不碰格式化 API：
     * 控件里存真值，画完控件后把输入框内部盖掉，再按长度画星号。只用 fill + 文本绘制，两族都有。</p>
     */
    protected final void drawPasswordMask(GuiGraphicsExtractor context, EditBox box, int x, int y) {
        if (box == null || box.getValue().isEmpty()) {
            return;
        }
        context.fill(x + 1, y + 1, x + INPUT_W - 1, y + BTN_H - 1, 0xFF000000);
        String masked = "\u002a".repeat(box.getValue().length());
        context.text(this.font, masked, x + 4, y + 6, 0xFFE0E0E0);
    }

}
