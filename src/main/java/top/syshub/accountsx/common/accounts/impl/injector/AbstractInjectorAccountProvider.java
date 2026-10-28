package top.syshub.accountsx.common.accounts.impl.injector;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import top.syshub.accountsx.common.auth.YggdrasilProfiles;
import top.syshub.accountsx.common.net.HttpGateway;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
 * <p>登录入口只有「先选服务器、再选登录方式」这一条：{@link #fetchDeviceCode} /
 * {@link #loginDeviceCode} / {@link #loginPassword}，由 {@code ExternalLoginScreen} 调用。
 * 服务器来自左栏筛选（{@link AuthServer}），因此这里没有通用表单，也不需要解析 locator。</p>
 */
public abstract class AbstractInjectorAccountProvider<T extends AbstractInjectorAccount> implements AccountProvider<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractInjectorAccountProvider.class);

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
    private final String accountContextName;

    protected final HttpGateway http;

    protected AbstractInjectorAccountProvider(String accountContextName, HttpGateway http) {
        this.accountContextName = accountContextName;
        this.http = http;
    }

    /** 该 provider 的服务器 kind（P3.1：locator → apiRoot 的解析逻辑随 kind 进注册表）。 */
    protected abstract AuthServerKind serverKind();

    protected abstract T createAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, String accountName, String avatarKey, long avatarCachedAt);

    @Override
    public final AccountContext createAccountContext(T account) throws IOException {
        String url = account.getServer();

        AuthServerMetadata metadata = registry().metadata(url);

        return new AccountContext(new AuthServerContext(
                url + "/authserver",
                url + "/api",
                url + "/sessionserver",
                url + "/minecraftservices",
                accountContextName,
                metadata.profileKeySupported()
        ), new AuthSecurityContext(
                metadata.profilePropertyKeys(), metadata.playerCertificateKeys(),
                SkinURLVerifier.ofOperationOR(SkinURLVerifier.ofDomainVerifier(metadata.skinDomains(), List.of()), SkinURLVerifier.MOJANG_DEFAULT)
        ), AuthPolicy.TRY);
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

    /**
     * 新登录界面：用户名 + 密码只换到「认证结果」，不决定角色。
     *
     * <p>一个账号在服务端可能有多个角色，绑哪个必须由用户选，所以这里先把
     * {@link YggdrasilPasswordFlow.LoginDraft} 交给界面：单角色（或服务端已选定）时界面直接调
     * {@link #loginPasswordSelected}，多角色时先让用户勾选。</p>
     */
    public YggdrasilPasswordFlow.LoginDraft loginPasswordDraft(AuthServer server, String username,
            String password) throws IOException {
        return YggdrasilPasswordFlow.authenticate(http, server, username, password);
    }

    /**
     * 为每个选中角色各建一个账号。
     *
     * <p>{@code /authserver/refresh} 一次只能绑定一个角色，绑定后令牌就属于那个角色了，所以除第一个
     * 复用 {@code draft} 外，其余角色都要重新认证一次再绑定——这也是界面只在这一步暂存用户名/密码、
     * 关闭界面立刻清空的原因。</p>
     */
    public List<T> loginPasswordSelected(AuthServer server, String username, String password,
            YggdrasilPasswordFlow.LoginDraft draft, List<YggdrasilProfiles.Profile> selected) throws IOException {
        List<T> accounts = new ArrayList<>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            YggdrasilProfiles.Profile profile = selected.get(i);
            YggdrasilPasswordFlow.LoginDraft current = i == 0
                    ? draft
                    : YggdrasilPasswordFlow.authenticate(http, server, username, password);
            YggdrasilAuthResult result = current.isBoundTo(profile)
                    ? YggdrasilPasswordFlow.resultFor(http, server, current, profile)
                    : YggdrasilPasswordFlow.bind(http, server, current, profile);
            accounts.add(toAccount(server, result));
        }
        return accounts;
    }

    /** 单角色快捷入口（无界面的调用方）：认证 + 自动绑定第一个角色。 */
    public T loginPassword(AuthServer server, String username, String password) throws IOException {
        return toAccount(server, YggdrasilPasswordFlow.login(http, server, username, password));
    }

    @Override
    public final void refresh(T account) throws IOException {
        if (AccountOAuth.isEnvelope(account.getLoginToken())) {
            // 校验 id_token 需要该服务器的 issuer / jwks_uri，只能从注册表拿完整条目。
            AuthServerRegistry servers = registry();
            AuthServer server = servers == null ? null : servers.byApiRoot(account.getServer());
            if (server == null)
                throw new IOException("认证服务器不在列表中，无法刷新账号: " + account.getServer());
            YggdrasilAuthResult result = YggdrasilDeviceCodeFlow.refresh(http,
                    account.getLoginToken().substring(AccountOAuth.PREFIX.length()), server);
            account.setProfile(result.accessToken(), result.playerName(), AccountUUID.parse(result.playerUuid()));
            account.setLoginProfile(AccountOAuth.envelope(result.refreshToken(), result.tokenEndpoint(), result.clientId()),
                    result.playerUuid());
            account.setAvatar(result.avatarKey(), result.avatarCachedAt());
            return;
        }
        YggdrasilPasswordFlow.refresh(http, account);
    }

    /**
     * 启动时先问服务端「这个令牌还能用吗」（B4）。能用就跳过刷新——**refresh 会作废旧令牌**，
     * 而账号 state 是 transient 的、每次启动都按未授权处理，无条件刷新等于每启动一次换一次令牌，
     * 同账号在别的客户端/实例上就立刻 401。
     *
     * <p>device-code 账号一样适用：validate 用的是 storage 里的 accessToken；loginToken 里放的是
     * OAuth 信封，不能拿去问服务器。</p>
     */
    @Override
    public boolean canReuseToken(T account) throws IOException {
        String accessToken = account.getAccountStorage().getAccessToken();
        if (accessToken == null || accessToken.isEmpty())
            return false;
        return YggdrasilPasswordFlow.validate(http, serverFor(account), accessToken, account.getClientToken());
    }

    /**
     * 删除账号时尽力让服务端作废令牌（B13）：{@code /authserver/invalidate} 是 Yggdrasil 的撤销端点。
     *
     * <p>必须自己吞掉异常——账号已经从界面上消失，这里失败最多留下一个服务端仍有效的令牌，
     * 不能让删除报错或回滚。令牌已经失效时服务端回 403，同样属于正常收尾，用
     * {@code ignoreHttpStatus=true} 避免把它当异常。</p>
     */
    @Override
    public void signOut(T account) {
        String accessToken = account.getAccountStorage().getAccessToken();
        if (accessToken == null || accessToken.isEmpty())
            return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("accessToken", accessToken);
            String clientToken = account.getClientToken();
            if (clientToken != null && !clientToken.isEmpty())
                body.addProperty("clientToken", clientToken);
            http.postJson(serverFor(account).apiRoot() + "/authserver/invalidate", body, true);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Cannot invalidate the token of the removed account.", e);
        }
    }

    private static AuthServer serverFor(AbstractInjectorAccount account) throws IOException {
        AuthServer server = registry().byApiRoot(account.getServer());
        if (server == null)
            throw new IOException("认证服务器不在列表中: " + account.getServer());
        return server;
    }

    /** 三种登录入口共用的落地映射：建账号 + clientToken + 有 refresh_token 就写 OAuth 信封。 */
    private T toAccount(AuthServer server, YggdrasilAuthResult result) {
        T account = createAccount(result.accessToken(), result.playerName(), AccountUUID.parse(result.playerUuid()),
                server.apiRoot(), result.playerUuid(), server.displayName(), result.avatarKey(), result.avatarCachedAt());
        if (result.clientToken() != null)
            account.setClientToken(result.clientToken());
        if (result.refreshToken() != null)
            account.setLoginProfile(AccountOAuth.envelope(result.refreshToken(), result.tokenEndpoint(), result.clientId()),
                    result.playerUuid());
        return account;
    }
}
