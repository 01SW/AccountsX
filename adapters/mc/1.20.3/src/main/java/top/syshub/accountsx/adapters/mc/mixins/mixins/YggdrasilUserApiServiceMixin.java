package top.syshub.accountsx.adapters.mc.mixins.mixins;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.mojang.authlib.Environment;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilUserApiService;
import com.mojang.authlib.yggdrasil.response.KeyPairResponse;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

@Mixin(value = YggdrasilUserApiService.class, remap = false)
public class YggdrasilUserApiServiceMixin {

    @Final
    @Shadow
    private Environment environment;

    @Unique
    private static final String MOJANG_SERVICES_HOST = "https://api.minecraftservices.com";

    /**
     * 只按本实例持有的 environment 判定，不引入“当前账号”式全局可变状态：
     * Mojang 正式环境的 servicesHost 固定，第三方注入（authlib-injector）指向自己的 API 根。
     */
    @Unique
    private boolean isThirdPartySession() {
        String host = environment.servicesHost();
        return host == null || !host.startsWith(MOJANG_SERVICES_HOST);
    }

    @Inject(
            method = "fetchProperties",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void defaultAttributes(CallbackInfoReturnable<UserApiService.UserProperties> cir) {
        if (isThirdPartySession())
            cir.setReturnValue(new UserApiService.UserProperties(
                ImmutableSet.of(
                        UserApiService.UserFlag.CHAT_ALLOWED,
                        UserApiService.UserFlag.SERVERS_ALLOWED,
                        UserApiService.UserFlag.REALMS_ALLOWED
                ),
                ImmutableMap.of()
            ));
    }

    @Inject(
            method = "isBlockedPlayer",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void notBlockedPlayer(UUID playerID, CallbackInfoReturnable<Boolean> cir) {
        if (isThirdPartySession()) cir.setReturnValue(false);
    }

    @Inject(
            method = "refreshBlockList",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void blankBlockList(CallbackInfo ci) {
        if (isThirdPartySession()) ci.cancel();
    }

    @Inject(
            method = "getKeyPair",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void getKeyPair(CallbackInfoReturnable<KeyPairResponse> cir) {
        if (isThirdPartySession()) {
            KeyPairGenerator generator;
            try {
                generator = KeyPairGenerator.getInstance("RSA");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            Base64.Encoder base64 = Base64.getMimeEncoder(76, "\n".getBytes(UTF_8));
            String privateKey = "-----BEGIN RSA PRIVATE KEY-----\n" + base64.encodeToString(keyPair.getPrivate().getEncoded()) + "\n-----END RSA PRIVATE KEY-----\n";
            String publicKey = "-----BEGIN RSA PUBLIC KEY-----\n" + base64.encodeToString(keyPair.getPublic().getEncoded()) + "\n-----END RSA PUBLIC KEY-----\n";

            Instant now = Instant.now();
            Instant expiresAt = now.plus(48, ChronoUnit.HOURS);
            Instant refreshedAfter = now.plus(36, ChronoUnit.HOURS);

            cir.setReturnValue(new KeyPairResponse(
                    new KeyPairResponse.KeyPair(
                            privateKey,
                            publicKey
                    ),
                    StandardCharsets.UTF_8.encode("AA=="),
                    DateTimeFormatter.ISO_INSTANT.format(expiresAt),
                    DateTimeFormatter.ISO_INSTANT.format(refreshedAfter)
            ));
        }
    }
}
