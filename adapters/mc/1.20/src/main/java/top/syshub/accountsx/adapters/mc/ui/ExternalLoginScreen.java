package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.auth.AuthServer;
import top.syshub.accountsx.common.auth.YggdrasilDeviceCodeFlow;
import top.syshub.accountsx.common.auth.YggdrasilDeviceCodeFlow.DeviceCode;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.ui.SelectionStyle;

/**
 * 外置认证登录界面（OAuth / 密码两种方式）。
 *
 * <p>服务器由左栏筛选确定，这里不再输入地址、也不发探测请求：端点全部取自注册表缓存。OAuth 分两步
 * ——先取设备码展示并复制，再打开浏览器并轮询 token。关闭窗口即取消：置 {@code closed} 后轮询与
 * 回调都会尽快放弃，迟到的结果不会写进账号列表。</p>
 */
public class ExternalLoginScreen extends LoginScreen {
    private static final int TOGGLE_W = (INPUT_W - PAD) / 2;

    private final AuthServer server;
    private final AbstractInjectorAccountProvider<?> provider;
    private final boolean oauthAvailable;

    private boolean oauthMode;
    private DeviceCode deviceCode;
    private int hintY;

    private EditBox usernameBox;
    private EditBox passwordBox;
    private int passwordX;
    private int passwordY;

    public ExternalLoginScreen(Screen parent, AuthServer server) {
        super(parent, "accountsx.account.general.login", label(server));
        this.server = server;
        this.provider = (AbstractInjectorAccountProvider<?>) AccountType.AUTHLIB_INJECTOR.getAccountProvider();
        this.oauthAvailable = YggdrasilDeviceCodeFlow.supportsDeviceCode(server);
        this.oauthMode = this.oauthAvailable;
    }

    private static Component label(AuthServer server) {
        String name = server.displayName();
        return Component.literal(name == null || name.isBlank() ? server.apiRoot() : name);
    }

    @Override
    protected void updateWidgets() {
        this.usernameBox = null;
        this.passwordBox = null;
        this.clearWidgets();

        int x = this.contentX();
        int y = TOP;

        if (this.oauthAvailable) {
            this.addField(new ButtonWidget(x, y, TOGGLE_W, BTN_H,
                    Component.translatable("accountsx.account.external.mode.oauth"),
                    button -> this.selectMode(true)));
            this.addField(new ButtonWidget(x + TOGGLE_W + PAD, y, INPUT_W - TOGGLE_W - PAD, BTN_H,
                    Component.translatable("accountsx.account.external.mode.password"),
                    button -> this.selectMode(false)));
            y += BTN_H + PAD * 2;
        }

        if (this.oauthMode) {
            DeviceCode code = this.deviceCode;
            if (code == null) {
                ButtonWidget fetch = new ButtonWidget(x, y, INPUT_W, BTN_H,
                        Component.translatable("accountsx.account.external.fetch_code"),
                        button -> this.fetchCode());
                fetch.active = !this.busy;
                this.addField(fetch);
            } else {
                // 拿到码之后给两个动作：直接去浏览器，或只把链接交给用户自己打开。
                ButtonWidget open = new ButtonWidget(x, y, TOGGLE_W, BTN_H,
                        Component.translatable("accountsx.account.external.open_browser"),
                        button -> this.loginOAuth(code));
                open.active = !this.busy;
                this.addField(open);
                ButtonWidget copy = new ButtonWidget(x + TOGGLE_W + PAD, y, INPUT_W - TOGGLE_W - PAD, BTN_H,
                        Component.translatable("accountsx.account.external.copy_link"),
                        button -> this.copyLink(code));
                copy.active = !this.busy;
                this.addField(copy);
            }
            y += BTN_H + PAD;
        } else {
            this.usernameBox = new EditBox(this.font, x, y, INPUT_W, BTN_H,
                    Component.translatable("accountsx.account.objects.user_name"));
            this.usernameBox.setHint(Component.translatable("accountsx.account.objects.user_name"));
            this.usernameBox.setMaxLength(128);
            this.addField(this.usernameBox);
            y += BTN_H + PAD;

            this.passwordX = x;
            this.passwordY = y;
            this.passwordBox = new EditBox(this.font, x, y, INPUT_W, BTN_H,
                    Component.translatable("accountsx.account.objects.user_password"));
            this.passwordBox.setHint(Component.translatable("accountsx.account.objects.user_password"));
            this.passwordBox.setMaxLength(128);
            this.addField(this.passwordBox);
            y += BTN_H + PAD;

            ButtonWidget login = new ButtonWidget(x, y, INPUT_W, BTN_H,
                    Component.translatable("accountsx.account.general.login"),
                    button -> this.loginPassword());
            login.active = !this.busy;
            this.addField(login);
            y += BTN_H + PAD;
        }

        this.hintY = y;
        this.addBackButton();
    }

    private void selectMode(boolean oauth) {
        if (this.busy || this.oauthMode == oauth) {
            return;
        }
        this.oauthMode = oauth;
        this.deviceCode = null;
        this.status = null;
        this.updateWidgets();
    }

    private void fetchCode() {
        if (this.busy) {
            return;
        }
        this.busy = true;
        this.status = Component.translatable("accountsx.account.external.code.generating");
        long rid = ++this.requestId;
        this.updateWidgets();

        TaskScheduler.submit(() -> {
            DeviceCode code = null;
            Component failure = null;
            try {
                code = this.provider.fetchDeviceCode(this.server);
            } catch (Exception e) {
                failure = failText(e);
            }

            DeviceCode result = code;
            Component error = failure;
            Minecraft minecraft = this.minecraft;
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (!this.valid(rid)) {
                    return;
                }
                this.busy = false;
                if (result == null) {
                    this.status = error;
                    this.updateWidgets();
                    return;
                }
                this.deviceCode = result;
                this.status = null;
                // 验证码是给人看的短码，复制进剪贴板省一次手抄。
                Platforms.getMinecraftPlatform().copyText(result.userCode());
                this.updateWidgets();
            });
        });
    }

    private void loginOAuth(DeviceCode code) {
        if (this.busy) {
            return;
        }
        String url = code.browserUrl();
        if (url != null) {
            Platforms.getMinecraftPlatform().openBrowser(url);
        }

        this.busy = true;
        this.status = Component.translatable("accountsx.account.general.operating");
        long rid = ++this.requestId;
        this.updateWidgets();

        TaskScheduler.submit(() -> {
            BaseAccount account = null;
            Component failure = null;
            try {
                account = this.provider.loginDeviceCode(this.server, code,
                        () -> this.closed || rid != this.requestId);
            } catch (Exception e) {
                failure = failText(e);
            }

            BaseAccount created = account;
            Component error = failure;
            Minecraft minecraft = this.minecraft;
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (!this.valid(rid)) {
                    return;
                }
                this.busy = false;
                if (created == null) {
                    this.status = error;
                    this.updateWidgets();
                    return;
                }
                this.finish(created);
            });
        });
    }

    private void loginPassword() {
        EditBox user = this.usernameBox;
        EditBox password = this.passwordBox;
        if (this.busy || user == null || password == null) {
            return;
        }
        String username = user.getValue().trim();
        if (username.isEmpty()) {
            this.status = Component.translatable("accountsx.account.external.error.username");
            return;
        }

        String secret = password.getValue();
        this.busy = true;
        this.status = Component.translatable("accountsx.account.general.operating");
        long rid = ++this.requestId;
        this.updateWidgets();

        TaskScheduler.submit(() -> {
            BaseAccount account = null;
            Component failure = null;
            try {
                account = this.provider.loginPassword(this.server, username, secret);
            } catch (Exception e) {
                failure = failText(e);
            }

            BaseAccount created = account;
            Component error = failure;
            Minecraft minecraft = this.minecraft;
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (!this.valid(rid)) {
                    return;
                }
                this.busy = false;
                if (created == null) {
                    this.status = error;
                    this.updateWidgets();
                    return;
                }
                this.finish(created);
            });
        });
    }

    private void copyLink(DeviceCode code) {
        String url = code.browserUrl();
        Platforms.getMinecraftPlatform().copyText(url == null ? code.verificationUri() : url);
    }

    /** 当前模式的底色标记：按钮本身没有 selected 样式，用与左侧筛选栏相同的背景带表达选中。 */
    private int modeX() {
        return this.oauthMode ? this.contentX() : this.contentX() + TOGGLE_W + PAD;
    }

    private int modeWidth() {
        return this.oauthMode ? TOGGLE_W : INPUT_W - TOGGLE_W - PAD;
    }

    /** 选中模式的外圈：必须先于控件绘制，否则会盖住按钮本身。 */
    private void drawModeFrame(GuiGraphics context) {
        if (!this.oauthAvailable) {
            return;
        }

        context.fill(this.modeX() - SelectionStyle.INSET_X, TOP - SelectionStyle.INSET_Y,
                this.modeX() + this.modeWidth() + SelectionStyle.INSET_X, TOP + BTN_H + SelectionStyle.INSET_Y,
                SelectionStyle.FRAME);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        this.drawHeader(context);
        this.drawModeFrame(context);
        super.render(context, mouseX, mouseY, delta);

        this.drawPasswordMask(context, this.passwordBox, this.passwordX, this.passwordY);

        if (this.oauthMode) {
            this.center(context, this.oauthHint(), this.hintY, 0xFFE0E0E0);
        }

        this.drawStatus(context);
    }

    /** OAuth 提示：没有设备码时说明下一步，有设备码时展示验证码与地址。 */
    private Component oauthHint() {
        DeviceCode code = this.deviceCode;
        if (code == null) {
            return this.oauthAvailable
                    ? Component.translatable("accountsx.account.external.hint.oauth")
                    : Component.translatable("accountsx.account.external.oauth_unavailable");
        }
        // 链接不占版面：用下面的「打开浏览器」「复制登录链接」按钮代替。
        return Component.translatable("accountsx.account.external.code", code.userCode());
    }
}
