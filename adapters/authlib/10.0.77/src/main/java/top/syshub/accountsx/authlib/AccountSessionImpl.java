package top.syshub.accountsx.authlib;

import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import com.mojang.authlib.services.ProfileResult;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.adapters.api.AccountSession;

/**
 * authlib 10 起 {@code YggdrasilAuthenticationService} 被 discovery 服务取代，
 * {@code MinecraftSessionService} 改名为 {@link SessionService}。
 */
public record AccountSessionImpl(
        BaseAccount.AccountStorage storage,
        MinecraftServicesDiscoveryService discoveryService,
        SessionService sessionService,
        UserApiService.UserProperties properties,
        UserApiService userAPIService,
        ProfileResult profileResult
) implements AccountSession {
}
