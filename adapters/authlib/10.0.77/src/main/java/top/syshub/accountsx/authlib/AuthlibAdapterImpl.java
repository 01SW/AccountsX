package top.syshub.accountsx.authlib;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.minecraft.TelemetrySession;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.services.*;
import com.mojang.authlib.services.request.AbuseReportRequest;
import com.mojang.authlib.services.response.KeyPairResponse;
import com.mojang.authlib.services.response.discovery.*;
import com.mojang.authlib.minecraft.client.MinecraftClient;
import com.mojang.authlib.minecraft.report.AbuseReportLimits;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.microsoft.MicrosoftConstants;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.accounts.model.context.AuthSecurityContext;
import top.syshub.accountsx.common.accounts.model.context.AuthServerContext;
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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * authlib 10.0.77：YggdrasilAuthenticationService 已删除，改为
 * {@link MinecraftServicesDiscoveryService} + 合成 {@link DiscoveryResponse}，
 * 以兼容 authlib-injector / United-Injector 的固定路径 Yggdrasil 端点。
 */
public final class AuthlibAdapterImpl implements AuthlibBridge<AccountSessionImpl> {

    // 1.1 修复：原先在 static 块里做网络 I/O，联网失败会抛 ExceptionInInitializerError。
    // 改为懒加载 + 失败降级为仅 Mojang 默认白名单。
    private static volatile AuthSecurityContext selectedSecurityField;

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthlibAdapterImpl.class);

    /** 懒加载并 memoize 的默认皮肤安全上下文；首次访问时才联网获取 Microsoft 公钥。 */
    public static AuthSecurityContext selectedSecurity() {
        AuthSecurityContext ctx = selectedSecurityField;
        if (ctx == null) {
            synchronized (AuthlibAdapterImpl.class) {
                ctx = selectedSecurityField;
                if (ctx == null) {
                    selectedSecurityField = ctx = computeDefaultSecurity();
                }
            }
        }
        return ctx;
    }

    private static AuthSecurityContext computeDefaultSecurity() {
        try {
            return MicrosoftConstants.computeMicrosoftPublicKeys();
        } catch (IOException e) {
            LOGGER.warn("无法获取 Microsoft 公钥，降级为仅 Mojang 默认皮肤白名单。", e);
            return new AuthSecurityContext(List.of(), List.of());
        }
    }

    @Override
    public AccountSessionImpl createAccountProfile(BaseAccount.AccountStorage storage, AccountContext context, Proxy proxy) throws IOException {
        if (context == null) {
            MinecraftServicesDiscoveryService discovery = MinecraftServicesDiscoveryService.createOffline(proxy);
            SessionService sessionService = discovery.createMinecraftSessionService();
            return new AccountSessionImpl(
                    storage, discovery, sessionService,
                    UserApiService.OFFLINE_PROPERTIES, UserApiService.OFFLINE,
                    computeProfile(storage, sessionService)
            );
        }

        AuthServerContext server = context.server();
        DiscoveryResponse discoveryResponse = buildDiscovery(server);
        MinecraftServicesDiscoveryService discovery = ofDiscoveryService(proxy, discoveryResponse);
        SessionService sessionService = ofSessionService(proxy, discovery, context.security());

        // 登录时用该账号自身的安全上下文覆盖默认（2.3 竞态留待 P4）
        selectedSecurityField = context.security();

        boolean lenient = !isMojangProd(server);
        UserApiService userAPIService = switch (context.policy()) {
            case ONLINE -> createUserApiService(discovery, proxy, storage.getAccessToken(), lenient);
            case OFFLINE -> UserApiService.OFFLINE;
            case TRY -> {
                try {
                    yield createUserApiService(discovery, proxy, storage.getAccessToken(), lenient);
                } catch (Exception e) {
                    yield UserApiService.OFFLINE;
                }
            }
        };

        return new AccountSessionImpl(storage, discovery, sessionService, switch (context.policy()) {
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
                } catch (Exception e) {
                    yield UserApiService.OFFLINE_PROPERTIES;
                }
            }
        }, userAPIService, computeProfile(storage, sessionService));
    }

    private static boolean isMojangProd(AuthServerContext server) {
        return "PROD".equals(server.name())
                && MicrosoftConstants.SERVICES.equals(server.serviceURL());
    }

    private static UserApiService createUserApiService(MinecraftServicesDiscoveryService discovery, Proxy proxy, String accessToken, boolean lenient) {
        if (lenient) {
            return new LenientUserApiService(accessToken, discovery, proxy);
        }
        return discovery.createUserApiService(accessToken);
    }

    // ── 合成 Discovery：把 AuthServerContext 的固定路径映射到 authlib 10 的端点名 ──

    private static DiscoveryResponse buildDiscovery(AuthServerContext server) {
        String session = trimSlash(server.sessionURL());
        String services = trimSlash(server.serviceURL());
        String accounts = trimSlash(server.accountURL());

        return new DiscoveryResponse(
                server.name(),
                "minecraft",
                new Discovery(
                        "minecraft",
                        new Endpoints(Map.of(
                                "getPublicKeys", new Endpoint(services + "/publickeys")
                        )),
                        new Endpoints(Map.of(
                                "join", new Endpoint(session + "/session/minecraft/join"),
                                "verify", new Endpoint(session + "/session/minecraft/hasJoined"),
                                "getProfileById", new Endpoint(session + "/session/minecraft/profile/{profileId}")
                        )),
                        new Endpoints(Map.of(
                                "getCertificates", new Endpoint(services + "/player/certificates"),
                                "getBlocklist", new Endpoint(services + "/privacy/blocklist"),
                                "getAttributes", new Endpoint(services + "/player/attributes"),
                                "sendReport", new Endpoint(services + "/player/report"),
                                "getFriends", new Endpoint(services + "/friends"),
                                "updateFriends", new Endpoint(services + "/friends"),
                                "updateAttributes", new Endpoint(services + "/player/attributes"),
                                "updatePresence", new Endpoint(services + "/presence")
                        )),
                        new Endpoints(Map.of(
                                "getManyByName", new Endpoint(accounts + "/profiles/minecraft"),
                                "getByName", new Endpoint(accounts + "/profiles/minecraft/{name}"),
                                "getTexture", new Endpoint(
                                        "https://textures.minecraft.net/texture/{textureId}",
                                        List.of("https://textures.minecraft.net/texture/")
                                )
                        )),
                        Endpoints.empty()
                )
        );
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static final MethodHandle MSDS_CTOR = UnsafeVM.prepareMH(
            "MinecraftServicesDiscoveryService::new",
            lookup -> lookup.findConstructor(
                    MinecraftServicesDiscoveryService.class,
                    MethodType.methodType(void.class, Proxy.class, boolean.class, Supplier.class)
            )
    );

    private static MinecraftServicesDiscoveryService ofDiscoveryService(Proxy proxy, DiscoveryResponse response) {
        try {
            // servicesKeySetEnabled=false：公钥由 AuthSecurityContext 注入，不再走 discovery 联网拉取
            return (MinecraftServicesDiscoveryService) MSDS_CTOR.invoke(proxy, false, (Supplier<DiscoveryResponse>) () -> response);
        } catch (Throwable t) {
            throw UnsafeVM.fail("MinecraftServicesDiscoveryService::new", t);
        }
    }

    /**
     * SessionService 构造器是 protected，子类在任意包均可调用；
     * 借此把 AuthSecurityContext 的公钥装进 ServicesKeySet，替代旧版字段注入。
     */
    private static final class KeyedSessionService extends MinecraftServicesSessionService {
        KeyedSessionService(ServicesKeySet keySet, Proxy proxy, MinecraftServicesDiscoveryService discovery) {
            super(keySet, proxy, discovery);
        }
    }

    private static SessionService ofSessionService(Proxy proxy, MinecraftServicesDiscoveryService discovery, AuthSecurityContext security) {
        ServicesKeySet keySet = customKeySet(security);
        return new KeyedSessionService(keySet, proxy, discovery);
    }

    private static ServicesKeySet customKeySet(AuthSecurityContext security) {
        List<ServicesKeyInfo> profilePropertyKeys = DefaultServicesKeyInfo.process(security.profilePropertyKeys());
        List<ServicesKeyInfo> playerCertificateKeys = DefaultServicesKeyInfo.process(security.playerCertificateKeys());
        return type -> switch (type) {
            case PROFILE_PROPERTY -> profilePropertyKeys;
            case PROFILE_KEY -> playerCertificateKeys;
        };
    }

    private static ProfileResult computeProfile(BaseAccount.AccountStorage storage, SessionService sessionService) {
        return sessionService.fetchProfile(storage.getPlayerUUID(), true);
    }

    /**
     * 注入器环境往往没有 player/attributes、privacy/blocklist、player/certificates 等端点。
     * 与旧版 YggdrasilUserApiServiceMixin 语义对齐：失败时回退到离线默认值，避免切号直接炸。
     */
    private static final class LenientUserApiService implements UserApiService {
        private final MinecraftServicesUserApiService delegate;

        LenientUserApiService(String accessToken, MinecraftServicesDiscoveryService discovery, Proxy proxy) {
            this.delegate = new MinecraftServicesUserApiService(accessToken, discovery, proxy);
        }

        @Override
        public UserProperties fetchProperties() throws AuthenticationException {
            try {
                return delegate.fetchProperties();
            } catch (Exception e) {
                return OFFLINE_PROPERTIES;
            }
        }

        @Override
        public boolean isBlockedPlayer(UUID playerID) {
            try {
                return delegate.isBlockedPlayer(playerID);
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void refreshBlockList() {
            try {
                delegate.refreshBlockList();
            } catch (Exception ignored) {
            }
        }

        @Override
        public KeyPairResponse getKeyPair() {
            try {
                return delegate.getKeyPair();
            } catch (Exception e) {
                return syntheticKeyPair();
            }
        }

        @Override
        public TelemetrySession newTelemetrySession(java.util.concurrent.Executor executor) {
            return delegate.newTelemetrySession(executor);
        }

        @Override
        public void reportAbuse(AbuseReportRequest request) {
            delegate.reportAbuse(request);
        }

        @Override
        public boolean canSendReports() {
            return false;
        }

        @Override
        public AbuseReportLimits getAbuseReportLimits() {
            return delegate.getAbuseReportLimits();
        }

        private static KeyPairResponse syntheticKeyPair() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair keyPair = generator.generateKeyPair();
                Base64.Encoder base64 = Base64.getMimeEncoder(76, "\n".getBytes(UTF_8));
                String privateKey = "-----BEGIN RSA PRIVATE KEY-----\n"
                        + base64.encodeToString(keyPair.getPrivate().getEncoded())
                        + "\n-----END RSA PRIVATE KEY-----\n";
                String publicKey = "-----BEGIN RSA PUBLIC KEY-----\n"
                        + base64.encodeToString(keyPair.getPublic().getEncoded())
                        + "\n-----END RSA PUBLIC KEY-----\n";
                Instant now = Instant.now();
                return new KeyPairResponse(
                        new KeyPairResponse.KeyPair(privateKey, publicKey),
                        StandardCharsets.UTF_8.encode("AA=="),
                        DateTimeFormatter.ISO_INSTANT.format(now.plus(48, ChronoUnit.HOURS)),
                        DateTimeFormatter.ISO_INSTANT.format(now.plus(36, ChronoUnit.HOURS))
                );
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("RSA unavailable", e);
            }
        }
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
