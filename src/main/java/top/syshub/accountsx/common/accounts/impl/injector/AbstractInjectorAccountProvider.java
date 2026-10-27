package top.syshub.accountsx.common.accounts.impl.injector;

import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.AccountUUID;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.accounts.model.context.AuthPolicy;
import top.syshub.accountsx.common.accounts.model.context.AuthSecurityContext;
import top.syshub.accountsx.common.accounts.model.context.AuthServerContext;
import top.syshub.accountsx.common.accounts.model.context.SkinURLVerifier;
import top.syshub.accountsx.common.auth.AccountOAuth;
import top.syshub.accountsx.common.auth.AuthServer;
import top.syshub.accountsx.common.auth.AuthServerKind;
import top.syshub.accountsx.common.auth.AuthServerMetadata;
import top.syshub.accountsx.common.auth.AuthServerRegistry;
import top.syshub.accountsx.common.auth.YggdrasilAuthResult;
import top.syshub.accountsx.common.auth.YggdrasilDeviceCodeFlow;
import top.syshub.accountsx.common.auth.YggdrasilDeviceCodeFlow.DeviceCode;
import top.syshub.accountsx.common.auth.YggdrasilPasswordFlow;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.ui.Memory;
import top.syshub.accountsx.common.ui.form.FieldKey;
import top.syshub.accountsx.common.ui.form.FormAction;
import top.syshub.accountsx.common.ui.form.FormField;
import top.syshub.accountsx.common.ui.form.FormSpec;
import top.syshub.accountsx.common.ui.form.ValidationOutcome;
import java.util.List;

import java.io.IOException;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * injector 系账号 provider：UI 编排 + 登录/刷新分发（P3.1 起变薄）。
 *
 * <p>网络编排拆到 {@code common/auth}：{@code YggdrasilServerProbe}（API-Location 解析 + 元数据）、
 * {@link YggdrasilPasswordFlow}（密码流）、{@link YggdrasilDeviceCodeFlow}（device-code）。
 * 公钥 / skinDomains / OIDC 端点不再每次登录现取——provider 只查
 * {@link AuthServerRegistry} 的内存缓存（启动预热 + 加服时探测，miss 时补测一次）。</p>
 *
 * <p>登录有两条入口：单表单快捷路径 {@link #login(Memory)}（服务器 + 凭据一次填完），以及
 * 「先选服务器、再选登录方式」的新界面用的 {@link #fetchDeviceCode} / {@link #loginDeviceCode} /
 * {@link #loginPassword}。</p>
 */
public abstract class AbstractInjectorAccountProvider<T extends AbstractInjectorAccount> implements AccountProvider<T> {

    private static volatile AuthServerRegistry registry;

    /** P5.3：注入服务器注册表，替代全局单例。 */
    public static void installRegistry(AuthServerRegistry r) {
        registry = r;
    }

    protected static AuthServerRegistry registry() {
        if (registry == null)
            throw new IllegalStateException("AuthServerRegistry 未注入。");
        return registry;
    }
    private static final String GUID_SERVER_BASE = FieldKey.SERVER.value();
    private static final String GUID_USER_NAME = FieldKey.USERNAME.value();
    private static final String GUID_PASSWORD = FieldKey.PASSWORD.value();
    private static final String GUID_PLAYER_NAME = FieldKey.PLAYER_NAME.value();

    private final String serverBaseTranslationKey;

    private final String userBaseTranslationKey;

    private final String accountContextName;

    protected final HttpGateway http;

    protected AbstractInjectorAccountProvider(String serverBaseTranslationKey, String userBaseTranslationKey, String accountContextName, HttpGateway http) {
        this.serverBaseTranslationKey = serverBaseTranslationKey;
        this.accountContextName = accountContextName;
        this.userBaseTranslationKey = userBaseTranslationKey;
        this.http = http;
    }

    /** 该 provider 的服务器 kind（P3.1：locator → apiRoot 的解析逻辑随 kind 进注册表）。 */
    protected abstract AuthServerKind serverKind();

    protected void validateServerBaseURL(String server) throws IllegalArgumentException {}

    protected abstract T createAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, String accountName, String avatarKey, long avatarCachedAt);

    /** device-code 流程的 UI/等待副作用；单测覆写为假实现以在无 MC 适配器环境驱动 OAuth。 */
    protected YggdrasilDeviceCodeFlow.Prompts devicePrompts() {
        return YggdrasilDeviceCodeFlow.PLATFORM_PROMPTS;
    }

    @Override
    public final AccountContext createAccountContext(T account) throws IOException {
        String url = account.getServer();

        AuthServerMetadata metadata = registry().metadata(url);

        return new AccountContext(new AuthServerContext(
                url + "/authserver",
                url + "/api",
                url + "/sessionserver",
                url + "/minecraftservices",
                accountContextName
        ), new AuthSecurityContext(
                metadata.profilePropertyKeys(), metadata.playerCertificateKeys(),
                SkinURLVerifier.ofOperationOR(SkinURLVerifier.ofDomainVerifier(metadata.skinDomains(), List.of()), SkinURLVerifier.MOJANG_DEFAULT)
        ), AuthPolicy.TRY);
    }

    @Override
    public final FormSpec form() {
        return new FormSpec("accountsx.account.general.login", List.of(
                new FormField.Text(FieldKey.SERVER, serverBaseTranslationKey, false, null),
                new FormField.Text(FieldKey.USERNAME, userBaseTranslationKey, false, null),
                new FormField.Text(FieldKey.PASSWORD, "accountsx.account.objects.user_password", true, null),
                new FormField.Text(FieldKey.PLAYER_NAME, "accountsx.account.objects.player_name", false, null)),
                List.of(
                        new FormAction("login", "accountsx.account.general.login", true),
                        new FormAction("close", "accountsx.general.action.close", false)));
    }

    @Override
    public final ValidationOutcome validate(Memory memory) throws IllegalArgumentException {
        String serverBase = memory.get(GUID_SERVER_BASE, String.class);
        validateServerBaseURL(serverBase);
        memory.set(GUID_SERVER_BASE, serverBase);
        memory.set(GUID_USER_NAME, memory.get(GUID_USER_NAME, String.class));
        memory.set(GUID_PASSWORD, memory.get(GUID_PASSWORD, String.class));
        memory.set(GUID_PLAYER_NAME, memory.get(GUID_PLAYER_NAME, String.class));

        return ValidationOutcome.CLOSE_IMMEDIATELY;
    }

    @Override
    public final T login(Memory memory) throws IOException {
        String locator = memory.get(GUID_SERVER_BASE, String.class);
        String username = memory.get(GUID_USER_NAME, String.class);

        // 加服语义：解析 + 探测 + 校验 + 去重；已知的服务器直接复用（不再发任何探测请求）。
        AuthServer server = registry().ensure(serverKind(), locator, OAuthConstants.OAUTH_CLIENT_IDS);

        YggdrasilAuthResult result;
        if (username.isEmpty())
            result = YggdrasilDeviceCodeFlow.login(http, devicePrompts(), server, OAuthConstants.OAUTH_CLIENT_IDS);
        else
            result = YggdrasilPasswordFlow.login(http, server, username,
                    memory.get(GUID_PASSWORD, String.class), memory.get(GUID_PLAYER_NAME, String.class));

        return toAccount(server, result);
    }

    /**
     * 新登录界面第一阶段：只从注册表缓存换设备码，不做端点探测。
     *
     * <p>端点缺失时抛 {@link IOException}——界面据此只显示密码登录，而不是在登录时偷偷联网探测。</p>
     */
    public DeviceCode fetchDeviceCode(AuthServer server) throws IOException {
        return YggdrasilDeviceCodeFlow.requestDeviceCode(http, server);
    }

    /** 新登录界面第二阶段：用户完成授权后轮询换取账号；{@code cancelled} 为真时尽快放弃。 */
    public T loginDeviceCode(AuthServer server, DeviceCode code, BooleanSupplier cancelled) throws IOException {
        return toAccount(server, YggdrasilDeviceCodeFlow.awaitToken(http, server, code,
                YggdrasilDeviceCodeFlow.cancellableSleep(cancelled), cancelled));
    }

    /** 新登录界面：用户名 + 密码直接换账号（不经过表单 {@link Memory}）。 */
    public T loginPassword(AuthServer server, String username, String password) throws IOException {
        return toAccount(server, YggdrasilPasswordFlow.login(http, server, username, password, ""));
    }

    @Override
    public final void refresh(T account) throws IOException {
        if (AccountOAuth.isEnvelope(account.getLoginToken())) {
            YggdrasilAuthResult result = YggdrasilDeviceCodeFlow.refresh(http,
                    account.getLoginToken().substring(AccountOAuth.PREFIX.length()), account.getServer());
            account.setProfile(result.accessToken(), result.playerName(), AccountUUID.parse(result.playerUuid()));
            account.setLoginProfile(AccountOAuth.envelope(result.refreshToken(), result.tokenEndpoint(), result.clientId()),
                    result.playerUuid());
            account.setAvatar(result.avatarKey(), result.avatarCachedAt());
            return;
        }
        YggdrasilPasswordFlow.refresh(http, account);
    }

    /** 三种登录入口共用的落地映射：建账号 + 有 refresh_token 就写 OAuth 信封。 */
    private T toAccount(AuthServer server, YggdrasilAuthResult result) {
        T account = createAccount(result.accessToken(), result.playerName(), AccountUUID.parse(result.playerUuid()),
                server.apiRoot(), result.playerUuid(), server.displayName(), result.avatarKey(), result.avatarCachedAt());
        if (result.refreshToken() != null)
            account.setLoginProfile(AccountOAuth.envelope(result.refreshToken(), result.tokenEndpoint(), result.clientId()),
                    result.playerUuid());
        return account;
    }
}
