package top.syshub.accountsx.authlib;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.authlib.Environment;
import com.mojang.authlib.HttpAuthenticationService;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.*;
import com.mojang.authlib.yggdrasil.response.MinecraftTexturesPayload;
import com.mojang.util.UUIDTypeAdapter;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.accounts.model.context.AuthSecurityContext;
import top.syshub.accountsx.common.adapters.api.AuthlibBridge;
import top.syshub.accountsx.common.utils.UnsafeVM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public final class AuthlibAdapterImpl implements AuthlibBridge<AccountSessionImpl> {
    @Override
    public AccountSessionImpl createAccountProfile(BaseAccount.AccountStorage storage, AccountContext context, Proxy proxy) throws IOException {
        if (context == null) {
            YggdrasilAuthenticationService service = new YggdrasilAuthenticationService(proxy);
            MinecraftSessionService sessionService = service.createMinecraftSessionService();

            return new AccountSessionImpl(
                    storage, service, sessionService,
                    UserApiService.OFFLINE_PROPERTIES, UserApiService.OFFLINE, computeProfile(storage, sessionService)
            );
        } else {
            Environment env = new Environment(context.server().sessionURL(), context.server().serviceURL(), context.server().name());
            YggdrasilAuthenticationService service = ofYggdrasilAuthenticationService(proxy, env, context.security());

            MinecraftSessionService sessionService = new PolicySessionService(service.getServicesKeySet(), service.getProxy(), env, context.security());

            UserApiService userAPIService = switch (context.policy()) {
                case ONLINE -> service.createUserApiService(storage.getAccessToken());
                case OFFLINE -> UserApiService.OFFLINE;
                case TRY -> {
                    try {
                        yield service.createUserApiService(storage.getAccessToken());
                    } catch (Exception e) {
                        yield UserApiService.OFFLINE;
                    }
                }
            };
            return new AccountSessionImpl(storage, service, sessionService, switch (context.policy()) {
                case ONLINE -> {
                    try {
                        yield userAPIService.fetchProperties();
                    } catch (AuthenticationException e) {
                        throw new IOException(e);
                    }
                }
                case OFFLINE -> UserApiService.OFFLINE_PROPERTIES;
                case TRY -> {
                    try {
                        yield userAPIService.fetchProperties();
                    } catch (AuthenticationException e) {
                        yield UserApiService.OFFLINE_PROPERTIES;
                    }
                }
            }, userAPIService, computeProfile(storage, sessionService));
        }
    }

    /**
     * 覆写 {@code unpackTextures}：签名校验后先按服务器的 skinDomains 判定贴图域名（P4 / P5.4）。
     *
     * <p>具名类而不是匿名类：白名单行为契约测试需要能直接构造它。</p>
     */
    static final class PolicySessionService extends YggdrasilMinecraftSessionService {
        private static final Logger LOGGER = LoggerFactory.getLogger(PolicySessionService.class);

        private final Gson gson = new GsonBuilder().registerTypeAdapter(UUID.class, new UUIDTypeAdapter()).create();

        private final AuthSecurityContext security;
        private final Function<Property, SignatureState> signatureStateResolver;

        PolicySessionService(ServicesKeySet keySet, Proxy proxy, Environment env, AuthSecurityContext security) {
            this(keySet, proxy, env, security, null);
        }

        /**
         * 测试注入点：单测没有 Fabric 运行时，UnsafeVM 取不到 IMPL_LOOKUP，
         * 无法构造指向 authlib 私有方法的 MethodHandle。
         */
        PolicySessionService(ServicesKeySet keySet, Proxy proxy, Environment env, AuthSecurityContext security,
                             Function<Property, SignatureState> signatureStateResolver) {
            super(keySet, proxy, env);
            this.security = security;
            this.signatureStateResolver = signatureStateResolver == null ? this::authlibSignatureState : signatureStateResolver;
        }

        private SignatureState authlibSignatureState(Property property) {
            try {
                return (SignatureState) SignatureStateAccessor.GET_PROPERTY_SIGNATURE_STATE.invoke(this, property);
            } catch (Throwable t) {
                throw UnsafeVM.fail("YggdrasilMinecraftSessionService::getPropertySignatureState", t);
            }
        }

        @Override
        public MinecraftProfileTextures unpackTextures(Property packedTextures) {
            String value = packedTextures.value();
            SignatureState signatureState = this.signatureStateResolver.apply(packedTextures);

            MinecraftTexturesPayload result;
            try {
                String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                result = this.gson.fromJson(json, MinecraftTexturesPayload.class);
            } catch (final JsonParseException | IllegalArgumentException e) {
                LOGGER.error("Could not decode textures payload", e);
                return MinecraftProfileTextures.EMPTY;
            }

            if (result == null || result.textures() == null || result.textures().isEmpty()) {
                return MinecraftProfileTextures.EMPTY;
            }

            for (MinecraftProfileTexture entry : result.textures().values()) {
                String url = entry.getUrl();
                if (security.shouldBlockSkinUrl(url)) {
                    LOGGER.error("Textures payload contains blocked domain: {}", url);
                    return MinecraftProfileTextures.EMPTY;
                }
            }

            Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = result.textures();

            return new MinecraftProfileTextures(
                    textures.get(MinecraftProfileTexture.Type.SKIN),
                    textures.get(MinecraftProfileTexture.Type.CAPE),
                    textures.get(MinecraftProfileTexture.Type.ELYTRA),
                    signatureState
            );

        }
        /** 延迟到真正用到时才解析 authlib 私有方法，避免单测在类初始化阶段依赖 UnsafeVM。 */
        private static final class SignatureStateAccessor {
            private static final MethodHandle GET_PROPERTY_SIGNATURE_STATE = UnsafeVM.prepareMH(
                    "YggdrasilMinecraftSessionService::getPropertySignatureState", lookup -> lookup.findVirtual(
                            YggdrasilMinecraftSessionService.class,
                            "getPropertySignatureState",
                            MethodType.methodType(SignatureState.class, Property.class)
                    )
            );
        }
    }
    private static final MethodHandle YASA_AL = UnsafeVM.getClassAllocator(YggdrasilAuthenticationService.class);

    private static final MethodHandle YASA_S_PROXY = UnsafeVM.prepareMH(
            "HttpAuthenticationService.proxy", lookup -> lookup.findSetter(HttpAuthenticationService.class, "proxy", Proxy.class)
    );

    private static final MethodHandle YASA_S_ENV = UnsafeVM.prepareMH(
            "YggdrasilAuthenticationService.environment", lookup -> lookup.findSetter(YggdrasilAuthenticationService.class, "environment", Environment.class)
    );

    private static final MethodHandle YASA_S_KS = UnsafeVM.prepareMH(
            "YggdrasilAuthenticationService.servicesKeySet", lookup -> lookup.findSetter(YggdrasilAuthenticationService.class, "servicesKeySet", ServicesKeySet.class)
    );

    private static YggdrasilAuthenticationService ofYggdrasilAuthenticationService(Proxy proxy, Environment env, AuthSecurityContext securityContext) {
        try {
            YggdrasilAuthenticationService service = (YggdrasilAuthenticationService) YASA_AL.invoke();
            YASA_S_PROXY.invoke(service, proxy);
            YASA_S_ENV.invoke(service, env);
            List<ServicesKeyInfo> profilePropertyKeys = DefaultServicesKeyInfo.process(securityContext.profilePropertyKeys());
            List<ServicesKeyInfo> playerCertificateKeys = DefaultServicesKeyInfo.process(securityContext.playerCertificateKeys());
            YASA_S_KS.invoke(service, (ServicesKeySet) type -> switch (type) {
                case PROFILE_PROPERTY -> profilePropertyKeys;
                case PROFILE_KEY -> playerCertificateKeys;
            });

            return service;
        } catch (Throwable t) {
            throw UnsafeVM.fail("YggdrasilAuthenticationService::new", t);
        }
    }

    private ProfileResult computeProfile(BaseAccount.AccountStorage storage, MinecraftSessionService sessionService) {
        return sessionService.fetchProfile(storage.getPlayerUUID(), true);
    }

    private record DefaultServicesKeyInfo(PublicKey publicKey) implements ServicesKeyInfo {
        public static List<ServicesKeyInfo> process(List<PublicKey> publicKeys) {
            return publicKeys.stream().<ServicesKeyInfo>map(DefaultServicesKeyInfo::new).toList();
        }

        private static final Logger LOGGER = LoggerFactory.getLogger(DefaultServicesKeyInfo.class);

        @Override
        public int keyBitCount() {
            return 4096;
        }

        @Override
        public Signature signature() {
            try {
                final Signature signature = Signature.getInstance("SHA1withRSA");
                signature.initVerify(publicKey);
                return signature;
            } catch (final NoSuchAlgorithmException | InvalidKeyException e) {
                throw new AssertionError("Failed to create signature", e);
            }
        }

        @Override
        public boolean validateProperty(Property property) {
            final Signature signature = signature();
            final byte[] expected;
            try {
                expected = Base64.getDecoder().decode(property.signature());
            } catch (final IllegalArgumentException e) {
                LOGGER.error("Malformed signature encoding on property {}", property, e);
                return false;
            }
            try {
                signature.update(property.value().getBytes());
                return signature.verify(expected);
            } catch (final SignatureException e) {
                LOGGER.error("Failed to verify signature on property {}", property, e);
            }
            return false;
        }
    }
}
