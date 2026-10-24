package top.syshub.accountsx.common.accounts.impl.injector;

import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.AccountUUID;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
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
import java.util.List;
import java.util.UUID;

/**
 * injector 系账号 provider：UI 编排 + 登录/刷新分发（P3.1 起变薄）。
 *
 * <p>网络编排拆到 {@code common/auth}：{@code YggdrasilServerProbe}（API-Location 解析 + 元数据）、
 * {@link YggdrasilPasswordFlow}（密码流）、{@link YggdrasilDeviceCodeFlow}（device-code）。
 * 公钥 / skinDomains / OIDC 端点不再每次登录现取——provider 只查
 * {@link AuthServerRegistry} 的内存缓存（启动预热 + 加服时探测，miss 时补测一次）。</p>
 *
 * <p>登录快捷路径（一个表单填服务器 + 凭据）保留：ensure 按 locator 去重，
 * 同服务器第二次登录不再发解析/探测请求。两级 UI（先加服再在服下加账号）属 P3.2。</p>
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

        T account = createAccount(result.accessToken(), result.playerName(), AccountUUID.parse(result.playerUuid()),
                server.apiRoot(), result.playerUuid(), server.displayName(), result.avatarKey(), result.avatarCachedAt());
        if (result.refreshToken() != null)
            account.setLoginProfile(AccountOAuth.envelope(result.refreshToken(), result.tokenEndpoint(), result.clientId()),
                    result.playerUuid());
        return account;
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
}
