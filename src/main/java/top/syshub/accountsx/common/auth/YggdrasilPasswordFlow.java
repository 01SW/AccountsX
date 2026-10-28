package top.syshub.accountsx.common.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.common.accounts.AccountUUID;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.impl.injector.InjectorAuthException;
import top.syshub.accountsx.common.accounts.model.PlayerNoLongerExistedException;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.utils.AvatarService;
import top.syshub.accountsx.common.utils.Threading;

/**
 * Yggdrasil 用户名/密码流程（P3.1：从 {@code AbstractInjectorAccountProvider} 拆出）。
 * apiRoot 取自注册表条目，不再每登录做一次 API-Location 解析。
 */
public final class YggdrasilPasswordFlow {
    /**
     * 生成 clientToken 用。drasl 自己用 {@code RandomHex(16)} 生成 32 个十六进制字符
     * （drasl {@code util.go}），Yggdrasil 规范也把 clientToken 限制在 32 字符以内，
     * 因此这里采用相同形状，避免只对 drasl 有效。
     */
    private static final SecureRandom CLIENT_TOKEN_RANDOM = new SecureRandom();

    /** 用本地 logger 而非 {@code AccountsX.LOGGER}：后者依赖 Fabric 运行时，单测类路径上加载会失败。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(YggdrasilPasswordFlow.class);

    private YggdrasilPasswordFlow() {
    }

    /** 生成一个 32 字符十六进制 clientToken。 */
    static String generateClientToken() {
        byte[] bytes = new byte[16];
        CLIENT_TOKEN_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 取服务端回显的 clientToken；缺省或为空时返回 {@code fallback}。
     *
     * <p>drasl 会把请求里的 clientToken 原样回显（{@code auth.go:271/393}），以服务端值为准
     * 可以对齐第三方实现可能做的规范化（例如补全/截断），避免下次刷新比对不上。</p>
     */
    private static String echoedClientToken(JsonObject json, String fallback) {
        if (json.get("clientToken") instanceof com.google.gson.JsonPrimitive echoed && echoed.isString()) {
            String value = echoed.getAsString();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return fallback;
    }

    /**
     * 统一把服务端的 Yggdrasil 错误响应转成 {@link InjectorAuthException}。
     * 注意只暴露服务端返回的 error / errorMessage，绝不带上任何令牌。
     *
     * <p>对 {@code "error"} 做类型判断而非直接 {@code getAsString()}：不规范的服务端可能把它写成
     * {@code null} 或对象，此时 {@code getAsString()} 会抛 {@link UnsupportedOperationException}，
     * 让本可操作的提示退化成「未知错误」并丢掉错误本身。</p>
     *
     * <p>i18n key 按调用场景区分：登录失败多半是用户名/密码写错（此时还没有账号可删），
     * 而刷新失败才是「凭据已失效」——沿用同一条提示会给出错误的操作建议。</p>
     */
    private static void throwIfError(JsonObject json, String translationKey) throws InjectorAuthException {
        JsonElement error = json.get("error");
        if (error == null || error.isJsonNull()) {
            return;
        }

        JsonElement errorMessage = json.get("errorMessage");
        String detail = errorMessage != null && errorMessage.isJsonPrimitive() ? errorMessage.getAsString() : "";
        throw new InjectorAuthException(
                translationKey,
                "Cannot auth this injector: " + error + (detail.isEmpty() ? "" : " - " + detail)
        );
    }

    /**
     * 取响应里的 accessToken；缺失或类型不对时抛 {@link IOException} 而不是 NPE。
     *
     * <p>认证请求是忽略 HTTP 状态码发出的（否则读不到 403 的错误体），因此任何非 2xx 响应都可能
     * 流到这里。drasl 的 500 响应是 {@code {"path":…,"errorMessage":"internal server error"}} ——
     * 没有 {@code error} 字段，{@link #throwIfError} 会放行；若此时直接取值，抛出的未检查异常会
     * 绕过 {@code AccountManager.refreshAccount} 的 IOException 分支，账号永远停在「登录中」。</p>
     */
    private static String requireAccessToken(JsonObject json) throws IOException {
        JsonElement accessToken = json.get("accessToken");
        if (accessToken == null || !accessToken.isJsonPrimitive() || !accessToken.getAsJsonPrimitive().isString()) {
            // 只报告字段名，绝不把响应体拼进消息：该消息会进日志，而某些 Yggdrasil 实现的错误体
            // 会回显 accessToken/clientToken，等于把凭据写进日志（违反 AGENTS.md 的安全约束）。
            throw new IOException("Yggdrasil response does not contain a string accessToken; fields=" + json.keySet());
        }
        return accessToken.getAsString();
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static YggdrasilAuthResult login(HttpGateway http, AuthServer server, String username,
            String password, String wantedPlayer) throws IOException {
        String apiRoot = server.apiRoot();
        String loginUrl = apiRoot + "/authserver/authenticate";
        String profileUrl = apiRoot + "/sessionserver/session/minecraft/profile/";

        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);

        // 主动提供 clientToken：服务端会把它存进该 client 并在 /authserver/refresh 强制比对。
        // 若省略，drasl 会为每次登录随机生成一个（auth.go:179-192），客户端一旦丢弃，
        // 之后的刷新必然 403 Invalid token —— 账号就变成「只有第一次能用」。
        String clientToken = generateClientToken();

        JsonObject root = new JsonObject();
        root.add("agent", agent);
        root.addProperty("username", username);
        root.addProperty("password", password);
        root.addProperty("clientToken", clientToken);

        // ignoreHttpStatus=true：Yggdrasil 的认证失败是标准 JSON 错误体（drasl 一律 403 + error/errorMessage），
        // 若先被网关的状态码校验拦下，就只剩 "HTTP 403"，用户看不到真正原因（也丢了 i18n 提示）。
        JsonObject json = http.postJson(loginUrl, root, true);
        throwIfError(json, "accountsx.account.fail.injector_login");

        String accessToken = requireAccessToken(json);
        clientToken = echoedClientToken(json, clientToken);
        List<YggdrasilProfiles.Profile> profiles = YggdrasilProfiles.readProfiles(json);

        if (profiles.size() == 1) {
            YggdrasilProfiles.Profile profile = profiles.get(0);
            if (wantedPlayer != null && !wantedPlayer.isEmpty() && !wantedPlayer.equals(profile.playerName()))
                throw new IOException("Player not found.");
            return withAvatar(http, profileUrl, profile, accessToken, clientToken);
        }
        for (YggdrasilProfiles.Profile profile : profiles) {
            if (profile.playerName().equals(wantedPlayer))
                return withAvatar(http, profileUrl, profile, accessToken, clientToken);
        }
        throw new PlayerNoLongerExistedException("Cannot find player which match " + wantedPlayer);
    }

    private static YggdrasilAuthResult withAvatar(HttpGateway http, String profileUrl,
            YggdrasilProfiles.Profile profile, String accessToken, String clientToken) {
        AvatarService.AvatarKey avatar = AvatarService.fetch(http, profileUrl, profile.playerUuid());
        return new YggdrasilAuthResult(accessToken, profile.playerName(), profile.playerUuid(),
                null, null, null, clientToken, avatar == null ? null : avatar.key(), avatar == null ? 0L : avatar.cachedAt());
    }

    /** 密码流刷新：沿用旧语义——token 轮换写回 loginToken，多 profile 时按 preferred 匹配并刷头像。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static void refresh(HttpGateway http, AbstractInjectorAccount account) throws IOException {
        String apiRoot = account.getServer();
        String refreshUrl = apiRoot + "/authserver/refresh";
        String profileUrl = apiRoot + "/sessionserver/session/minecraft/profile/";

        String clientToken = account.getClientToken();
        if (clientToken == null || clientToken.isEmpty()) {
            // 旧版本（2.0.0-beta.3 及更早）登录时未保存 clientToken，且当时也没有保存密码，
            // 无法凭现有载荷补出能通过服务端比对的值；只能让用户重新添加该账号。
            //
            // 已用真机（drasl 4.0.1）确认没有更省事的退路：refresh 省略 clientToken 与发送空字符串
            // 都稳定返回 403 ForbiddenOperationException: Invalid token，只有携带正确值才 200。
            // 因此这里直接给出可操作提示，不再白发一次注定失败的请求。
            throw new InjectorAuthException(
                    "accountsx.account.fail.injector_invalid_token",
                    "This injector account has no clientToken (created by an older version); add it again."
            );
        }

        JsonObject root = new JsonObject();
        root.addProperty("accessToken", account.getLoginToken());
        root.addProperty("clientToken", clientToken);

        // 同上：必须读得到 403 的错误体，才能把「凭据已失效」这类可操作原因告诉用户。
        JsonObject json = http.postJson(refreshUrl, root, true);
        throwIfError(json, "accountsx.account.fail.injector_invalid_token");

        String accessToken = requireAccessToken(json);
        String echoed = echoedClientToken(json, clientToken);
        if (!echoed.equals(clientToken)) {
            account.setClientToken(echoed);
        }

        // 关键顺序：**立刻**提交服务端已经轮换好的令牌，再去解析角色。
        // 刷新一旦被服务端接受，旧 accessToken 就已经作废（drasl 递增版本号）；若此时因为角色解析
        // 失败而把新令牌丢掉，账号会永久卡在「旧令牌已死、新令牌没保存」，只能重新添加。
        // 角色解析失败因此降级为「保留旧档案 + 记一条警告」，不再影响令牌落地。
        account.setLoginProfile(accessToken, account.getPreferredPlayerUUID());
        account.setProfile(accessToken, account.getAccountStorage().getPlayerName(), account.getAccountStorage().getPlayerUUID());

        YggdrasilProfiles.Profile profile = resolveRefreshedProfile(account, json);
        if (profile == null) {
            return;
        }

        account.setLoginProfile(accessToken, profile.playerUuid());
        account.setProfile(accessToken, profile.playerName(), AccountUUID.parse(profile.playerUuid()));

        // 头像属于可选附加信息，失败由 AvatarService 自行吞掉。
        AvatarService.AvatarKey avatar = AvatarService.fetch(http, profileUrl, profile.playerUuid());
        account.setAvatar(avatar == null ? null : avatar.key(), avatar == null ? 0L : avatar.cachedAt());
    }

    /**
     * 解析刷新响应里的角色；解析不出来时保留账号原有档案并记警告（返回 {@code null}）。
     * 刷新本身已经成功，绝不能因为角色信息异常把整次刷新判为失败。
     */
    private static YggdrasilProfiles.Profile resolveRefreshedProfile(AbstractInjectorAccount account, JsonObject json) {
        try {
            List<YggdrasilProfiles.Profile> profiles = YggdrasilProfiles.readProfiles(json);
            if (profiles.size() == 1) {
                return profiles.get(0);
            }

            String preferredPlayerUUID = account.getPreferredPlayerUUID();
            for (YggdrasilProfiles.Profile profile : profiles) {
                if (profile.playerUuid().equals(preferredPlayerUUID)) {
                    return profile;
                }
            }

            // 服务端不再返回首选角色（例如角色已被删除、或服务端只回 selectedProfile）。
            LOGGER.warn("Refreshed profile list does not contain the preferred player {}; keeping the previous profile.", preferredPlayerUUID);
            return null;
        } catch (RuntimeException e) {
            // 解析失败（字段缺失、类型不对等）不再影响令牌落地。
            LOGGER.warn("Cannot resolve the refreshed profile; keeping the previous one.", e);
            return null;
        }
    }
}
