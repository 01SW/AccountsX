package top.syshub.accountsx.common.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.utils.Threading;

/**
 * 认证服务器注册表（P3.1 / 待办 2）：servers 条目的内存视图 + 元数据缓存。
 *
 * <p>语义（03 路线图 P3.1）：元数据（公钥 / skinDomains / OIDC 端点）只在<b>启动</b>
 * （{@link #refreshAll}，逐 server 拉一次，失败标 stale 不阻塞）与<b>加服</b>
 * （{@link #ensure}，失败直接抛、不落盘）时取；登录 / 刷新 / createAccountContext 只读内存
 * （miss 时兜底补测一次——重启后 refreshAll 还没跑完的窗口）。</p>
 *
 * <p>不做 TTL 过期：公钥轮换极少，过期反而引入登录中途失效的竞态。</p>
 *
 * <p>线程模型：条目与元数据在各自方法内同步；{@code persist(JsonArray)} 由
 * {@code AccountRepository.snapshot()} 在仓储锁内调用（锁序恒为 仓储→注册表；
 * 变更回调在注册表锁外触发，避免反向持锁）。</p>
 */
public final class AuthServerRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthServerRegistry.class);

    private final HttpGateway http;

    /** 条目变化回调（持久化标脏）。可为 null（测试 / 游离注册表）。 */
    private final Runnable onChange;

    private final Map<String, AuthServer> servers = new LinkedHashMap<>();

    private final Map<String, AuthServerMetadata> metadata = new LinkedHashMap<>();

    public AuthServerRegistry(HttpGateway http) {
        this(http, null);
    }

    public AuthServerRegistry(HttpGateway http, Runnable onChange) {
        this.http = Objects.requireNonNull(http, "http");
        this.onChange = onChange;
    }

    public synchronized List<AuthServer> servers() {
        return List.copyOf(servers.values());
    }

    /** 按 apiRoot 查条目；固定条目（mojang/offline）的键就是 kind 字符串。 */
    public synchronized AuthServer byApiRoot(String apiRoot) {
        return apiRoot == null ? null : servers.get(apiRoot);
    }

    public synchronized AuthServerMetadata metadataOrNull(String apiRoot) {
        return apiRoot == null ? null : metadata.get(apiRoot);
    }

    /** 只读内存；miss 时兜底探测一次（启动预热未完成 / 首次见到该 apiRoot 的旧账号）。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public AuthServerMetadata metadata(String apiRoot) throws IOException {
        AuthServerMetadata cached = metadataOrNull(apiRoot);
        if (cached != null)
            return cached;
        AuthServerMetadata probed = YggdrasilServerProbe.readMetadata(http.get(apiRoot));
        synchronized (this) {
            metadata.put(apiRoot, probed);
        }
        return probed;
    }

    /** 启动装载：读 servers 数组 + 补齐 mojang / offline 固定条目。 */
    public synchronized void load(JsonArray array) {
        servers.clear();
        metadata.clear();
        for (AuthServer server : AuthServer.readAll(array)) {
            // builtin kind（mojang / offline）不进入 servers 列表
            if (server.kind().builtin())
                continue;
            servers.put(server.key(), server);
        }
        // mojang / offline 是 builtin kind，不进入 servers 列表（它们没有任何可配置内容）。
        // byApiRoot 对 builtin 返回 null；createAccountContext 对应类型不查注册表。
    }

    /**
     * 写回 servers 数组（仓储 snapshot 在其锁内调用）。
     *
     * <p>按条目键对齐原 JSON，保留我们没建模的未知字段（与 {@code ConfigRoot} 的
     * "未知字段原样回写"原则一致：新版本写入 → 旧版本保存不应丢数据）。</p>
     */
    public synchronized void persist(JsonArray array) {
        Map<String, JsonObject> previous = new LinkedHashMap<>();
        for (JsonElement element : array) {
            if (element instanceof JsonObject jo)
                previous.put(AuthServer.keyOf(jo), jo);
        }
        List<JsonObject> next = new ArrayList<>(servers.size());
        for (AuthServer server : servers.values()) {
            JsonObject jo = server.toJson();
            JsonObject old = previous.get(server.key());
            if (old != null) {
                for (String field : old.keySet()) {
                    if (!jo.has(field))
                        jo.add(field, old.get(field));
                }
            }
            next.add(jo);
        }
        while (!array.isEmpty())
            array.remove(0);
        for (JsonObject jo : next)
            array.add(jo);
    }

    /** 加服（P3.2 UI 入口；P3.1 由登录快捷路径隐式使用）：解析 → 探测 → 校验 → 去重。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public AuthServer ensure(AuthServerKind kind, String locator, Map<String, String> knownClientIds)
            throws IOException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(locator, "locator");
        if (kind.builtin()) {
            // builtin kind 不落 servers 列表；返回合成条目，不持久化。
            synchronized (this) {
                AuthServer existing = servers.get(kind.v4type());
                if (existing != null)
                    return existing;
            }
            return new AuthServer(null, kind, null, null, null, 0L);
        }
        String trimmed = locator.trim();
        // 同 locator 同 kind 已知 → 连 API-Location 解析都省掉（登录快捷路径每次都带 locator，
        // 否则 HEAD 会跟着登录次数线性增长——P3.1 验收"同服务器只探测一次"要求 0 次）。
        synchronized (this) {
            for (AuthServer known : servers.values()) {
                if (known.kind() == kind && trimmed.equals(known.locator()))
                    return known;
            }
        }
        String apiRoot = resolveApiRoot(kind, trimmed);
        boolean locatorBackfilled = false;
        synchronized (this) {
            AuthServer existing = servers.get(apiRoot);
            if (existing != null) {
                // 迁移/预热失败的条目可能 locator 为 null：按 apiRoot 命中时回填，避免每次快捷登录重跑 HEAD 链。
                if (existing.locator() == null) {
                    servers.put(apiRoot, new AuthServer(existing.apiRoot(), existing.kind(),
                            existing.displayName(), trimmed, existing.oauth(), existing.cachedAt()));
                    locatorBackfilled = true;
                }
                return servers.get(apiRoot);
            }
        }
        if (locatorBackfilled && onChange != null)
            onChange.run();
        AuthServer probed = probe(apiRoot, kind, trimmed, knownClientIds);
        boolean inserted;
        synchronized (this) {
            inserted = servers.putIfAbsent(apiRoot, probed) == null;
            if (!inserted)
                probed = servers.get(apiRoot);
        }
        if (inserted && onChange != null)
            onChange.run();
        return probed;
    }

    /** 启动预热：逐个探测 injector 条目；失败保留旧条目（stale），不阻塞。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public void refreshAll(Map<String, String> knownClientIds) {
        List<AuthServer> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(servers.values());
        }
        boolean changed = false;
        for (AuthServer server : snapshot) {
            if (server.kind().builtin())
                continue;
            try {
                String locator = server.locator() != null ? server.locator() : server.apiRoot();
                AuthServer probed = probe(server.apiRoot(), server.kind(), locator, knownClientIds);
                AuthServer merged = new AuthServer(server.apiRoot(), server.kind(),
                        probed.displayName() != null ? probed.displayName() : server.displayName(),
                        locator,
                        OAuthEndpoints.merge(probed.oauth(), server.oauth()),
                        probed.cachedAt());
                synchronized (this) {
                    if (servers.containsKey(server.apiRoot()))
                        servers.put(server.apiRoot(), merged);
                }
                changed = true;
            } catch (Exception e) {
                LOGGER.warn("认证服务器启动探测失败，保留旧元数据: {}", server.apiRoot(), e);
            }
        }
        if (changed && onChange != null)
            onChange.run();
    }

    /** locator → apiRoot。United 是固定前缀拼接；Yggdrasil 走最多 10 跳 HEAD 解析。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public String resolveApiRoot(AuthServerKind kind, String locator) throws IOException {
        return switch (kind) {
            case UNITED -> "https://auth.mc-user.com:233/" + locator;
            case YGGDRASIL -> YggdrasilServerProbe.resolveApiRoot(locator, http);
            case MOJANG, OFFLINE -> locator;
        };
    }

    /** 探测一个 apiRoot：GET 根路径 → 公钥 / skinDomains / displayName / OIDC 端点。不落 servers，只填元数据缓存。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public AuthServer probe(String apiRoot, AuthServerKind kind, String locator, Map<String, String> knownClientIds)
            throws IOException {
        YggdrasilServerProbe.Probed probed = YggdrasilServerProbe.probe(http, apiRoot, locator, knownClientIds);
        synchronized (this) {
            metadata.put(apiRoot, probed.metadata());
        }
        return new AuthServer(apiRoot, kind, probed.displayName(), locator, probed.oauth(),
                System.currentTimeMillis());
    }

    /**
     * 登录期兜底发现的 OAuth 端点回写（P3.1 c2）：预热失败的条目在 device 登录成功后
     * 把 discover 结果并回 servers[] 并落盘，避免每次登录重复 GET 根+openid。
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public void updateOAuthEndpoints(String apiRoot, OAuthEndpoints oauth) {
        boolean changed = false;
        synchronized (this) {
            AuthServer existing = apiRoot == null ? null : servers.get(apiRoot);
            if (existing != null && oauth != null && !Objects.equals(existing.oauth(), oauth)) {
                servers.put(apiRoot, new AuthServer(existing.apiRoot(), existing.kind(),
                        existing.displayName(), existing.locator(),
                        OAuthEndpoints.merge(oauth, existing.oauth()), existing.cachedAt()));
                changed = true;
            }
        }
        if (changed && onChange != null)
            onChange.run();
    }
}
