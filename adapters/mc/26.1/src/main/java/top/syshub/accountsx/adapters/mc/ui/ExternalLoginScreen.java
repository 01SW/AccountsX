package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.auth.AuthServer;
import top.syshub.accountsx.common.auth.AuthServerKind;
import top.syshub.accountsx.common.auth.YggdrasilDeviceCodeFlow;
import top.syshub.accountsx.common.auth.YggdrasilDeviceCodeFlow.DeviceCode;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.task.TaskScheduler;

/**
 * 外置认证登录界面（OAuth / 密码两种方式）。
 *
 * <p>服务器由左栏筛选确定，这里不再输入地址、也不发探测请求：端点全部取自注册表缓存。OAuth 分两步
 * ——先取设备码展示给用户，再打开浏览器并轮询 token。关闭窗口即取消：置 {@code closed} 后轮询与
 * 回调都会尽快放弃，迟到的结果不会写进账号列表。</p>
 */
public class ExternalLoginScreen extends Screen {
    private static final int PAD = 8;
    private static final int BTN_H = 20;
    private static final int INPUT_W = 240;
    private static final int TOGGLE_W = (INPUT_W - PAD) / 2;
    private static final int TOP = 96;

    private final Screen parent;
    private final AuthServer server;
    private final AbstractInjectorAccountProvider<?> provider;
    private final boolean oauthAvailable;

    /** 轮询线程会读这两个字段，写入全在客户端线程，故声明 volatile。 */
    private volatile boolean closed;
    private volatile long requestId;

    private boolean oauthMode;
    private boolean busy;
    private DeviceCode deviceCode;
    private Component status;

    private EditBox usernameBox;
    private EditBox passwordBox;
    private int passwordX;
    private int passwordY;

    public ExternalLoginScreen(Screen parent, AuthServer server) {
        super(Component.translatable("accountsx.account.general.login"));
        this.parent = parent;
        this.server = server;
        AccountType type = server.kind() == AuthServerKind.UNITED
                ? AccountType.UNITED_INJECTOR
                : AccountType.AUTHLIB_INJECTOR;
        this.provider = (AbstractInjectorAccountProvider<?>) type.getAccountProvider();
        this.oauthAvailable = YggdrasilDeviceCodeFlow.supportsDeviceCode(server);
        this.oauthMode = this.oauthAvailable;
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

    private void updateWidgets() {
        this.usernameBox = null;
        this.passwordBox = null;
        this.clearWidgets();

        int x = this.width / 2 - INPUT_W / 2;
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
            Component label = Component.translatable(this.deviceCode == null
                    ? "accountsx.account.external.fetch_code"
                    : "accountsx.account.external.open_browser");
            ButtonWidget action = new ButtonWidget(x, y, INPUT_W, BTN_H, label, button -> this.startOAuth());
            action.active = !this.busy;
            this.addField(action);
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
        }

        // 关闭按钮始终可点：忙碌时它就是「取消」入口（onClose 会终止轮询）。
        this.addField(new ButtonWidget(x, this.height - PAD - BTN_H, INPUT_W, BTN_H,
                Component.translatable("accountsx.general.action.close"),
                button -> this.onClose()));
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

    /** 界面状态是否仍代表当前这次操作：关窗或又发起了新操作都会让它失真。 */
    private boolean valid(long rid) {
        return !this.closed && rid == this.requestId;
    }

    private void startOAuth() {
        DeviceCode code = this.deviceCode;
        if (code == null) {
            this.fetchCode();
            return;
        }
        this.loginOAuth(code);
    }

    private void fetchCode() {
        if (this.busy) {
            return;
        }
        this.busy = true;
        this.status = Component.translatable("accountsx.account.oauth2.code.generating");
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

    /** 登录成功：入库、让父界面的列表跟上，然后回到父界面。 */
    private void finish(BaseAccount account) {
        AccountManager.addAccount(account);
        Screen parentScreen = this.parent;
        if (parentScreen instanceof AccountScreen accountScreen) {
            accountScreen.syncAccounts();
        }
        this.onClose();
    }

    private static Component failText(Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return Component.translatable("accountsx.account.server.fail", detail);
    }

    private void addField(AbstractWidget drawable) {
        this.addRenderableOnly(drawable);
        this.addWidget(drawable);
    }

    /**
     * 密码框遮蔽。
     *
     * <p>各版本 EditBox 的格式化 API 不同（26.x 只有 {@code addFormatter}，1.20 家族没有统一入口），
     * 而这份代码要同时编译 12 个版本，所以不碰格式化 API：控件里存真值，画完控件后把输入框内部盖掉，
     * 再按长度画星号。只用 fill + 文本绘制，两族都有。</p>
     */
    private void drawPasswordMask(GuiGraphicsExtractor context) {
        EditBox box = this.passwordBox;
        if (box == null || box.getValue().isEmpty()) {
            return;
        }
        context.fill(this.passwordX + 1, this.passwordY + 1,
                this.passwordX + INPUT_W - 1, this.passwordY + BTN_H - 1, 0xFF000000);
        String masked = "\u002a".repeat(box.getValue().length());
        context.text(this.font, masked, this.passwordX + 4, this.passwordY + 6, 0xE0E0E0);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0x99000000);
        super.extractRenderState(context, mouseX, mouseY, delta);

        this.drawPasswordMask(context);

        int centerX = this.width / 2;
        context.centeredText(this.font, this.title, centerX, 40, 0xFFFFFF);
        String name = this.server.displayName();
        Component serverLabel = Component.literal(name == null || name.isBlank() ? this.server.apiRoot() : name);
        context.centeredText(this.font, serverLabel, centerX, 56, 0xA0A0A0);

        if (this.oauthMode) {
            int hintY = TOP + BTN_H + PAD * 3 + BTN_H;
            context.centeredText(this.font, this.oauthHint(), centerX, hintY, 0x808080);
        }

        if (this.status != null) {
            context.centeredText(this.font, this.status, centerX, this.height - PAD - BTN_H - 16, 0xA0A0A0);
        }
    }

    /** OAuth 提示：没有设备码时说明下一步，有设备码时展示验证码与地址。 */
    private Component oauthHint() {
        DeviceCode code = this.deviceCode;
        if (code == null) {
            return this.oauthAvailable
                    ? Component.translatable("accountsx.account.external.hint.oauth")
                    : Component.translatable("accountsx.account.external.oauth_unavailable");
        }
        String url = code.browserUrl();
        Component base = Component.translatable("accountsx.account.external.code", code.userCode());
        if (url == null) {
            return base;
        }
        String shown = this.font.plainSubstrByWidth(url, INPUT_W * 2);
        return base.copy().append(Component.literal("  " + shown));
    }
}
