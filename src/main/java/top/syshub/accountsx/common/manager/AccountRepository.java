package top.syshub.accountsx.common.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.common.accounts.AccountUUID;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.env.EnvironmentAccount;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.impl.injector.impl.UnitedInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.impl.microsoft.MicrosoftAccount;
import top.syshub.accountsx.common.accounts.impl.offline.OfflineAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.storage.ConfigRoot;
import top.syshub.accountsx.common.storage.ConfigStore;
import top.syshub.accountsx.common.task.TaskScheduler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 账号仓储（P2.4）：内存账号集合 + 当前账号指针 + 持久化编排的新家。
 *
 * <p>旧 {@code AccountManager} 只剩编排（刷新/登录），集合操作（add / drop / move）与
 * "当前账号"状态全搬到这里。P3 会再给出 typed 的账号/服务器模型；本阶段仓储只做
 * JsonObject（{@link ConfigRoot} 的 live 数组）与 {@link BaseAccount} 之间的双向映射，
 * 不提前绑定模型。</p>
 *
 * <p>写路径是"变更即标脏 + 调度器串行合并写"：{@link #markDirty()} 只在串行通道上
 * 排一次写任务，N 次连续变更合并为一次落盘。读失败/只读降级时 {@link ConfigStore#save}
 * 本来就会丢弃写请求，所以这里不需要再判 {@code isReadOnly}——禁写语义由存储层保证。</p>
 */
public final class AccountRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountRepository.class);

    private final ConfigStore store;
    private volatile ConfigRoot root;
    private final List<BaseAccount> accounts = new ArrayList<>();
    private volatile BaseAccount current;

    /** 已排队但尚未执行的写任务（合并写用；串行通道保序，volatile 可见性足够）。 */
    private volatile boolean writePending;

    public AccountRepository(ConfigStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 启动加载：环境账号打头 + 首启动旧配置触发迁移 + 新存储 load()。
     *
     * <p>顺序刻意是"先触发迁移、再 load"：触发器直接跑 3→4 落盘 v4（若旧实例配置存在
     * 且全局尚无账号）；随后 load() 读到已迁移的 v4，不再走链。load() 的"文件缺失则落盘空 v4"
     * 只发生在无旧配置可迁的真·首启动。重复触发安全（全局已有账号则跳过；3→4 自带追加去重）。</p>
     *
     * @param legacyGameConfigDir 旧实例配置目录（{@code <game>/config/accountsx/}），
     *                            触发迁移用；传 null 表示"不要尝试迁移"（测试/无实例目录场景）
     */
    public synchronized void initialize(BaseAccount environment, Path legacyGameConfigDir) {
        accounts.clear();
        if (environment != null)
            accounts.add(environment);
        current = environment;

        boolean migratedNow = triggerLegacyMigration(legacyGameConfigDir);
        // 注意顺序：触发器必须在 load() 之前跑。首启动时全局文件不存在，
        // 触发器通过 MigrationContext.globalConfigFile() 定位并直接落盘 v4；
        // 随后 load() 读到已迁移的 v4，不再走链、不落空文件覆盖。
        root = store.load();
        if (migratedNow)
            LOGGER.info("启动迁移完成。");
        for (JsonElement element : root.accounts()) {
            if (!(element instanceof JsonObject jo)) {
                LOGGER.warn("全局配置含非对象账号条目，已跳过。");
                continue;
            }
            try {
                BaseAccount account = fromJson(jo);
                if (account != null)
                    accounts.add(account);
                else
                    LOGGER.warn("全局配置含未知类型账号条目，已跳过（不删除，P3 注册表会接管未知条目）。");
            } catch (RuntimeException e) {
                // 单条坏了只跳过该条：未知 type 在 1→2 已移除，这里只防手工改坏的字段。
                LOGGER.warn("全局配置含无法解析的账号条目，已跳过。", e);
            }
        }
    }

    /**
     * 首启动触发：全局文件是"刚落盘的空配置"（无账号）且旧实例配置存在时，
     * 把旧实例配置当作 v3 文档跑一次 3→4。
     * 3→4 自带追加去重，所以重复触发安全；全局文件已有账号时直接跳过。
     *
     * @return 是否实际跑过迁移（跑过则 load 结果已过期，调用方需重读）
     */
    private boolean triggerLegacyMigration(Path legacyGameConfigDir) {
        if (legacyGameConfigDir == null)
            return false;
        Path legacyConfig = legacyGameConfigDir.resolve("accounts.json");
        if (!Files.isRegularFile(legacyConfig))
            // 实例配置不存在 = 无旧数据可迁（真·首启动或已删实例目录），直接跳过
            return false;
        try {
            String text = Files.readString(legacyConfig, StandardCharsets.UTF_8);
            JsonObject legacy = parseObject(text);
            if (legacy == null)
                return false;
            if (!(legacy.get("version") instanceof JsonPrimitive vp) || !vp.isNumber()
                    || vp.getAsInt() != 3 || str(legacy.get("id")) == null)
                return false;
            // 全局文件已有账号 = 迁过了（或其他实例迁的），绝不重复触发。
            JsonObject global = readGlobalRaw(store);
            if (global != null && global.get("accounts") instanceof JsonArray ga && !ga.isEmpty())
                return false;
            top.syshub.accountsx.common.storage.migration.StandardMigrations
                    .chain(top.syshub.accountsx.common.utils.NetworkUtils.GSON)
                    .upgrade(legacy, 3, storeContext());
            return true;
        } catch (Exception e) {
            // 触发失败不阻塞启动：旧文件原样保留，下次启动重试；链内部失败同样降级。
            LOGGER.warn("旧实例配置迁移触发失败（旧文件保留，下次启动重试）。", e);
            return false;
        }
    }

    private static JsonObject parseObject(String text) {
        try {
            JsonElement parsed = top.syshub.accountsx.common.utils.NetworkUtils.GSON.fromJson(text, JsonElement.class);
            return parsed instanceof JsonObject jo ? jo : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 直接读全局文件原文（不走 {@link ConfigStore#load}——load 会落盘/迁移/回写，
     * 在触发器判断里用它等于边读边改；且装饰器包装的 store 读的是同一份文件，
     * 路径必须从 {@link #storeContext} 的 {@code globalConfigFile()} 取）。
     */
    private JsonObject readGlobalRaw(ConfigStore store) {
        try {
            java.nio.file.Path global = storeContext().globalConfigFile();
            if (global == null || !Files.isRegularFile(global))
                return null;
            String text = Files.readString(global, StandardCharsets.UTF_8);
            return parseObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    private top.syshub.accountsx.common.storage.migration.MigrationContext storeContext() {
        // store 可能是测试包装（装饰器）：JsonConfigStore 要求的是 path()/migrationContext() 两个能力，
        // 不要求具体类——仓储永远经这个小接口拿，不做 instanceof 强转。
        if (store instanceof Contextual contextual)
            return contextual.migrationContext();
        if (store instanceof top.syshub.accountsx.common.storage.JsonConfigStore jcs)
            return jcs.migrationContext();
        throw new IllegalStateException("AccountRepository 需要带 MigrationContext 的 ConfigStore 才能触发迁移");
    }

    /** 能提供迁移执行环境的 ConfigStore（JsonConfigStore 原生实现；测试装饰器按需实现）。 */
    interface Contextual {
        top.syshub.accountsx.common.storage.migration.MigrationContext migrationContext();
    }

    // ── 集合操作（CLIENT 线程，沿用 AccountManager 的线程契约） ──

    public synchronized List<BaseAccount> view() {
        return List.copyOf(accounts);
    }

    public BaseAccount getCurrent() {
        return current;
    }

    public synchronized void setCurrent(BaseAccount account) {
        current = account;
    }

    public synchronized void add(BaseAccount account) {
        accounts.add(Objects.requireNonNull(account, "account"));
        root.accounts().add(toJson(account));
        markDirty();
    }

    public synchronized void drop(BaseAccount account) {
        if (account != null && accounts.remove(account)) {
            removeJson(account);
            markDirty();
        }
    }

    public synchronized void move(BaseAccount account, int index) {
        if (account == null || !accounts.remove(account))
            return;
        accounts.add(Math.max(0, Math.min(index, accounts.size())), account);
        rebuildJson();
        markDirty();
    }

    /** 登录/刷新后 storage 变化需要落盘时调用（只标脏，不重建 JSON——对象映射在写时统一做）。 */
    public synchronized void touch(BaseAccount account) {
        if (account != null && accounts.contains(account))
            markDirty();
    }

    private void removeJson(BaseAccount account) {
        JsonArray array = root.accounts();
        for (int i = array.size() - 1; i >= 0; i--) {
            if (sameEntry(array.get(i), account)) {
                array.remove(i);
                return;
            }
        }
    }

    private void rebuildJson() {
        JsonArray array = root.accounts();
        while (!array.isEmpty())
            array.remove(0);
        for (BaseAccount account : accounts) {
            if (account.getAccountType() != AccountType.ENV_DEFAULT)
                array.add(toJson(account));
        }
    }

    /** 写时统一从对象重建 JSON：保证 login/refresh 改过的 storage 一定落盘。 */
    private void snapshot() {
        rebuildJson();
    }

    // ── 标脏合并写 ──

    /**
     * 在串行通道上排一个写任务；已有一个在排队就不再排（合并）。
     * 写任务执行时先 {@link #snapshot()} 再 {@link ConfigStore#save}，
     * 所以任务排队期间发生的后续变更也会被同一批写带走。
     */
    public void markDirty() {
        synchronized (this) {
            if (writePending)
                return;
            writePending = true;
        }
        TaskScheduler.submit(() -> {
            ConfigRoot toSave;
            synchronized (this) {
                writePending = false;
                snapshot();
                toSave = root;
            }
            try {
                store.save(toSave);
            } catch (IOException e) {
                LOGGER.error("账号配置保存失败（内存状态不受影响，下次变更触发重试）。", e);
            }
        });
    }

    // ── 新↔旧双向映射 ──

    static String str(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    private static boolean sameEntry(JsonElement element, BaseAccount account) {
        if (!(element instanceof JsonObject jo))
            return false;
        String type = str(jo.get("type"));
        if (type == null || !type.equals(configIdOf(account.getAccountType())))
            return false;
        JsonObject profile = jo.get("profile") instanceof JsonObject p ? p : null;
        UUID want = account.getAccountStorage().getPlayerUUID();
        if (profile == null || want == null)
            return profile == null && want == null && type.equals("offline");
        return want.toString().replace("-", "").equalsIgnoreCase(str(profile.get("uuid")));
    }

    /**
     * v4 JSON → 旧对象（登录/刷新/UI 复用整条旧链路，P3 再换模型）。
     * 未知 type 返回 null（条目保留在 root.accounts() 里，不删除）。
     */
    static BaseAccount fromJson(JsonObject jo) {
        String type = str(jo.get("type"));
        // v4 用短 kind（yggdrasil / united），旧链路用 configId：两边都认
        AccountType accountType = type == null ? null : byConfigId(type);
        if (accountType == null)
            accountType = byKind(type);
        if (accountType == null)
            return null;
        JsonObject profile = jo.get("profile") instanceof JsonObject p ? p : new JsonObject();
        String name = str(profile.get("name"));
        UUID uuid = parseUuid(str(profile.get("uuid")));
        JsonObject credentials = jo.get("credentials") instanceof JsonObject c ? c : new JsonObject();
        String accessToken = str(credentials.get("accessToken"));
        String avatarKey = str(jo.get("avatarKey"));

        String server = str(jo.get("server"));
        JsonObject envelope = oauthEnvelope(credentials);
        return switch (accountType) {
            case OFFLINE -> new OfflineAccount(accessToken, name, uuid);
            case MICROSOFT -> new MicrosoftAccount(accessToken, name, uuid, null, str(credentials.get("refreshToken")), avatarKey, 0L);
            case AUTHLIB_INJECTOR -> {
                var account = new AuthlibInjectorAccountProvider.AuthlibInjectorAccount(
                        accessToken, name, uuid, server, uuid32(str(profile.get("uuid"))),
                        str(jo.get("accountName")), avatarKey, 0L);
                if (envelope != null)
                    account.setLoginTokenForMigration("OAuth " + envelope, uuid32(str(profile.get("uuid"))));
                yield account;
            }
            case UNITED_INJECTOR -> new UnitedInjectorAccountProvider.UnitedInjectorAccount(
                    accessToken, name, uuid, server, uuid32(str(profile.get("uuid"))), null, avatarKey, 0L);
            default -> null;
        };
    }

    private static AccountType byConfigId(String configId) {
        for (AccountType t : AccountType.CONFIGURABLE_VALUES) {
            if (configId.equals(configIdOf(t)))
                return t;
        }
        return null;
    }

    /** v4 的结构化 OAuth 还原为旧链路信封（P3 换掉 provider 链路后删除）。 */
    private static JsonObject oauthEnvelope(JsonObject credentials) {
        if (credentials == null || !"true".equals(String.valueOf(credentials.get("oauth"))))
            return null;
        String endpoint = str(credentials.get("tokenEndpoint"));
        String refresh = str(credentials.get("refreshToken"));
        String clientId = str(credentials.get("clientId"));
        if (endpoint == null || refresh == null || clientId == null)
            return null;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("token_endpoint", endpoint);
        envelope.addProperty("refresh_token", refresh);
        envelope.addProperty("client_id", clientId);
        return envelope;
    }

    /** v4 存无横线 uuid；旧对象要带横线形式，preferredPlayerUUID 同理。 */
    private static String uuid32(String raw) {
        if (raw == null)
            return null;
        String hex = raw.replace("-", "");
        return hex.length() == 32 ? hex : raw;
    }

    private static AccountType byKind(String kind) {
        if (kind == null)
            return null;
        return switch (kind) {
            case "yggdrasil" -> AccountType.AUTHLIB_INJECTOR;
            case "united" -> AccountType.UNITED_INJECTOR;
            case "offline" -> AccountType.OFFLINE;
            case "microsoft" -> AccountType.MICROSOFT;
            default -> null;
        };
    }

    /** configId 持久化契约：枚举第三个构造参数（见 AGENTS.md 硬性不变量）。 */
    private static String configIdOf(AccountType type) {
        return switch (type) {
            case ENV_DEFAULT -> null;
            case OFFLINE -> "offline";
            case MICROSOFT -> "microsoft";
            case AUTHLIB_INJECTOR -> "injector.authlib-injector";
            case UNITED_INJECTOR -> "injector.united";
        };
    }

    private static UUID parseUuid(String raw) {
        if (raw == null)
            return null;
        try {
            return AccountUUID.parse(raw);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("账号条目 uuid 非法，已按 null 处理：{}", raw);
            return null;
        }
    }

    /**
     * 旧对象 → v4 JSON。OAuth 信封在 v4 侧是结构化字段，这里把它还原回去
     * （{@code loginToken = "OAuth {…}"}），让旧刷新链路无缝工作；
     * P3 换掉 provider 链路后这段还原逻辑随之删除。
     */
    static JsonObject toJson(BaseAccount account) {
        JsonObject jo = new JsonObject();
        // v4 用短 kind；旧链路读回时 fromJson 两边都认
        String kind = switch (account.getAccountType()) {
            case AUTHLIB_INJECTOR -> "yggdrasil";
            case UNITED_INJECTOR -> "united";
            case OFFLINE -> "offline";
            case MICROSOFT -> "microsoft";
            case ENV_DEFAULT -> null;
        };
        if (kind == null)
            throw new IllegalArgumentException("环境账号不落盘: " + account);
        jo.addProperty("type", kind);
        BaseAccount.AccountStorage storage = account.getAccountStorage();
        if (storage.getPlayerName() != null || storage.getPlayerUUID() != null) {
            JsonObject profile = new JsonObject();
            if (storage.getPlayerName() != null)
                profile.addProperty("name", storage.getPlayerName());
            if (storage.getPlayerUUID() != null)
                profile.addProperty("uuid", AccountUUID.toMinecraftStyleString(storage.getPlayerUUID()));
            jo.add("profile", profile);
        }
        boolean injector = account instanceof top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
        if (injector) {
            var injectorAccount = (top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount) account;
            if (injectorAccount.getServer() != null)
                jo.addProperty("server", injectorAccount.getServer());
            if (injectorAccount.getAccountName() != null)
                jo.addProperty("accountName", injectorAccount.getAccountName());
        }
        JsonObject credentials = new JsonObject();
        if (injector) {
            var injectorAccount = (top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount) account;
            String loginToken = injectorAccount.getLoginToken();
            if (loginToken != null && loginToken.startsWith("OAuth ")) {
                // v4 结构化 OAuth 还原为旧链路的信封：login 时 setLoginProfile 写的就是这个形状
                credentials.addProperty("oauth", true);
                if (storage.getAccessToken() != null)
                    credentials.addProperty("accessToken", storage.getAccessToken());
                try {
                    JsonObject envelope = parseObject(loginToken.substring("OAuth ".length()));
                    if (envelope == null
                            || str(envelope.get("token_endpoint")) == null
                            || str(envelope.get("refresh_token")) == null
                            || str(envelope.get("client_id")) == null)
                        throw new IllegalStateException("bad envelope");
                    credentials.addProperty("tokenEndpoint", str(envelope.get("token_endpoint")));
                    credentials.addProperty("refreshToken", str(envelope.get("refresh_token")));
                    credentials.addProperty("clientId", str(envelope.get("client_id")));
                } catch (RuntimeException e) {
                    LOGGER.warn("内存中的 OAuth 信封已损坏，仅保留 oauth 标记与 accessToken。", e);
                }
            } else if (loginToken != null) {
                credentials.addProperty("accessToken", loginToken);
            } else if (storage.getAccessToken() != null) {
                credentials.addProperty("accessToken", storage.getAccessToken());
            }
        } else if (storage.getAccessToken() != null) {
            credentials.addProperty("accessToken", storage.getAccessToken());
        }
        if (account instanceof MicrosoftAccount ms && ms.getMicrosoftAccountRefreshToken() != null)
            credentials.addProperty("refreshToken", ms.getMicrosoftAccountRefreshToken());
        if (!credentials.isEmpty())
            jo.add("credentials", credentials);
        if (account.getAvatarKey() != null)
            jo.addProperty("avatarKey", account.getAvatarKey());
        return jo;
    }

    // ── 诊断 ──

    ConfigStore store() {
        return store;
    }

    synchronized boolean isWritePending() {
        return writePending;
    }
}