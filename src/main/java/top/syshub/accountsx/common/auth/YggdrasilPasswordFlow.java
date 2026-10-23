package top.syshub.accountsx.common.auth;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import top.syshub.accountsx.common.accounts.AccountUUID;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.model.PlayerNoLongerExistedException;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.utils.AvatarService;
import top.syshub.accountsx.common.utils.Threading;

/**
 * Yggdrasil 用户名/密码流程（P3.1：从 {@code AbstractInjectorAccountProvider} 拆出）。
 * apiRoot 取自注册表条目，不再每登录做一次 API-Location 解析。
 */
public final class YggdrasilPasswordFlow {
    private YggdrasilPasswordFlow() {
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

        JsonObject root = new JsonObject();
        root.add("agent", agent);
        root.addProperty("username", username);
        root.addProperty("password", password);

        JsonObject json = http.postJson(loginUrl, root);
        if (json.has("error"))
            throw new IOException("Cannot auth this injector: " + json.get("errorMessage").getAsString());
        String accessToken = json.get("accessToken").getAsString();
        List<YggdrasilProfiles.Profile> profiles = YggdrasilProfiles.readProfiles(json);

        if (profiles.size() == 1) {
            YggdrasilProfiles.Profile profile = profiles.get(0);
            if (wantedPlayer != null && !wantedPlayer.isEmpty() && !wantedPlayer.equals(profile.playerName()))
                throw new IOException("Player not found.");
            return withAvatar(http, profileUrl, profile, accessToken);
        }
        for (YggdrasilProfiles.Profile profile : profiles) {
            if (profile.playerName().equals(wantedPlayer))
                return withAvatar(http, profileUrl, profile, accessToken);
        }
        throw new PlayerNoLongerExistedException("Cannot find player which match " + wantedPlayer);
    }

    private static YggdrasilAuthResult withAvatar(HttpGateway http, String profileUrl,
            YggdrasilProfiles.Profile profile, String accessToken) {
        AvatarService.AvatarKey avatar = AvatarService.fetch(http, profileUrl, profile.playerUuid());
        return new YggdrasilAuthResult(accessToken, profile.playerName(), profile.playerUuid(),
                null, null, null, avatar == null ? null : avatar.key(), avatar == null ? 0L : avatar.cachedAt());
    }

    /** 密码流刷新：沿用旧语义——token 轮换写回 loginToken，多 profile 时按 preferred 匹配并刷头像。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static void refresh(HttpGateway http, AbstractInjectorAccount account) throws IOException {
        String apiRoot = account.getServer();
        String refreshUrl = apiRoot + "/authserver/refresh";
        String profileUrl = apiRoot + "/sessionserver/session/minecraft/profile/";

        JsonObject root = new JsonObject();
        root.addProperty("accessToken", account.getLoginToken());

        JsonObject json = http.postJson(refreshUrl, root);
        if (json.has("error"))
            throw new IOException("Cannot auth this injector: " + json.get("errorMessage").getAsString());
        String accessToken = json.get("accessToken").getAsString();

        List<YggdrasilProfiles.Profile> profiles = YggdrasilProfiles.readProfiles(json);
        if (profiles.size() == 1) {
            YggdrasilProfiles.Profile profile = profiles.get(0);
            account.setLoginProfile(accessToken, profile.playerUuid());
            account.setProfile(accessToken, profile.playerName(), AccountUUID.parse(profile.playerUuid()));
            return;
        }
        String preferredPlayerUUID = account.getPreferredPlayerUUID();
        for (YggdrasilProfiles.Profile profile : profiles) {
            if (profile.playerUuid().equals(preferredPlayerUUID)) {
                account.setLoginProfile(accessToken, profile.playerUuid());
                account.setProfile(accessToken, profile.playerName(), AccountUUID.parse(profile.playerUuid()));
                AvatarService.AvatarKey avatar = AvatarService.fetch(http, profileUrl, profile.playerUuid());
                account.setAvatar(avatar == null ? null : avatar.key(), avatar == null ? 0L : avatar.cachedAt());
                return;
            }
        }
        throw new PlayerNoLongerExistedException("Cannot find player which match " + preferredPlayerUUID);
    }
}
