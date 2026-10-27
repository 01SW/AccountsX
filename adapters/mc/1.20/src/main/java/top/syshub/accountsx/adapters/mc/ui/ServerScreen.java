package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.auth.AuthServerKind;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.task.TaskScheduler;


/**
 * 添加外置认证服务器。
 *
 * <p>输入认证服务器地址后探测一次，成功即写入全局配置并返回账号列表界面——父界面重建时会重新
 * 生成左侧筛选，新服务器立刻可见。</p>
 */
public class ServerScreen extends Screen {
    private static final int PAD = 8;
    private static final int BTN_H = 20;
    private static final int INPUT_W = 300;

    private final Screen parent;
    private EditBox urlInput;
    private ButtonWidget addButton;
    private boolean busy;
    private Component status;

    public ServerScreen(Screen parent) {
        super(Component.translatable("accountsx.account.general.add_auth_server"));
        this.parent = parent;
    }

    public void onClose() {
        assert this.minecraft != null;

        this.minecraft.setScreen(this.parent);
    }

    @Override
    protected void init() {
        super.init();

        int inputX = this.width / 2 - INPUT_W / 2;
        int inputY = this.height / 2 - 30;

        this.urlInput = new EditBox(this.font, inputX, inputY, INPUT_W, BTN_H,
                Component.translatable("accountsx.account.general.server_url"));
        this.urlInput.setHint(Component.translatable("accountsx.account.general.server_url"));
        this.urlInput.setMaxLength(256);
        this.addField(this.urlInput);

        int half = (INPUT_W - PAD) / 2;
        this.addField(new ButtonWidget(inputX, inputY + BTN_H + PAD, half, BTN_H,
                Component.translatable("accountsx.general.action.close"),
                button -> this.onClose()));

        this.addButton = new ButtonWidget(inputX + half + PAD, inputY + BTN_H + PAD, half, BTN_H,
                Component.translatable("accountsx.account.general.add_server"),
                button -> this.submit());
        this.addButton.active = !this.busy;
        this.addField(this.addButton);
    }

    private void submit() {
        if (this.urlInput == null) {
            return;
        }

        String locator = this.urlInput.getValue().trim();
        if (locator.isEmpty()) {
            this.status = Component.translatable("accountsx.account.server.fail.empty");
            return;
        }

        this.busy = true;
        this.status = Component.translatable("accountsx.account.server.probing");
        this.addButton.active = false;

        TaskScheduler.submit(() -> {
            Component failure = null;
            try {
                AccountManager.authServers().ensure(AuthServerKind.YGGDRASIL, locator);
            } catch (Exception e) {
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                failure = Component.translatable("accountsx.account.server.fail", detail);
            }

            Component result = failure;
            if (this.minecraft == null) {
                return;
            }

            this.minecraft.execute(() -> {
                this.busy = false;
                this.status = result;
                if (result == null) {
                    this.onClose();
                    return;
                }

                if (this.addButton != null) {
                    this.addButton.active = true;
                }
            });
        });
    }

    private void addField(AbstractWidget drawable) {
        this.addRenderableOnly(drawable);
        this.addWidget(drawable);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0x99000000);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFFF);
        if (this.status != null) {
            context.drawCenteredString(this.font, this.status, this.width / 2, this.height / 2 + 10, 0xA0A0A0);
        }
    }
}
