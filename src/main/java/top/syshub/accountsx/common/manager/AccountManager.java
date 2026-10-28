package top.syshub.accountsx.common.manager;

import top.syshub.accountsx.common.AccountsX;
import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountState;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.accounts.impl.injector.InjectorProfileKeys;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.adapters.api.AccountSession;
import top.syshub.accountsx.common.auth.AuthServer;
import top.syshub.accountsx.common.storage.ConfigStore;
import top.syshub.accountsx.common.storage.JsonConfigStore;
import top.syshub.accountsx.common.storage.migration.StandardMigrations;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.utils.NetworkUtils;
import top.syshub.accountsx.common.utils.Threading;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public final class AccountManager {
    /**
     * 正在刷新的账号 → 该次刷新的完成信号。
     *
     * <p>启动批次和用户点击可能同时刷新同一个账号：两次刷新会各自拿一个令牌，
     * 后落盘的覆盖先落盘的，而先落盘的那个已经在服务端作废，账号随即失效。
     * 因此同一账号同一时刻只允许一次刷新，后来者等待前一次的结果。</p>
     */
    private static final ConcurrentHashMap<BaseAccount, CompletableFuture<Void>> PENDING_REFRESHES =
            new ConcurrentHashMap<>();


    private static AccountRepository repository;

    private AccountManager() {
    }

    static AccountRepository repository() {
        if (repository == null)
            throw new IllegalStateException("AccountManager 未初始化（initialize 未调用）。");
        return repository;
    }

    public static List<BaseAccount> getAccountsView() {
        return repository().view();
    }

    public static BaseAccount getCurrentAccount() {
        return repository().getCurrent();
    }

    public static top.syshub.accountsx.common.auth.AuthServerRegistry authServers() {
        return repository().authServers();
    }

    /** 账号是否属于某个外置认证服务器（UI 筛选与「删服务器连带删账号」共用同一判定）。 */
    public static boolean isOnServer(BaseAccount account, String apiRoot) {
        return AccountRepository.isOnServer(account, apiRoot);
    }

    public static void initialize() {
        ConfigStore store = JsonConfigStore.createDefault(StandardMigrations.chain(NetworkUtils.GSON));
        repository = new AccountRepository(store);
        // 环境账号打头（运行时派生，永不落盘），随后是新存储的账号
        repository.initialize(Platforms.getMinecraftPlatform().fromCurrentClient(),
                FabricLoader.getInstance().getConfigDir().resolve("accountsx"));

        // P3.1：服务器元数据启动预热（串行通道，逐 server 一次；失败标 stale 不阻塞启动）
        repository.warmAuthServers();

        List<BaseAccount> toRefresh = new ArrayList<>();
        for (BaseAccount account : repository.view()) {
            if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
                toRefresh.add(account);
            }
        }

        if (!toRefresh.isEmpty()) {
            List<TaskScheduler.Task> refreshTasks = new ArrayList<>(toRefresh.size());
            // 刷新失败在内部被消化成 UNAUTHORIZED，任务本身不会异常，因此单独计数给用户一个交代：
            // 启动刷新在后台跑，用户看不到日志，只会在列表里看到账号变成"未登录"。
            AtomicInteger failed = new AtomicInteger();
            for (BaseAccount account : toRefresh) {
                refreshTasks.add(() -> {
                    refreshAccount(account, false);
                    if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
                        failed.incrementAndGet();
                    }
                });
            }
            TaskScheduler.runParallel(refreshTasks).whenComplete((ignored, t) -> {
                if (t != null) {
                    AccountsX.LOGGER.warn("Some accounts failed to refresh during startup.", t);
                }
                int count = failed.get();
                if (count > 0) {
                    AccountsX.LOGGER.warn("{} account(s) could not be refreshed during startup.", count);
                    Platforms.getMinecraftPlatform().showToast(
                            "accountsx.account.fail.title", "accountsx.account.fail.startup_refresh", count);
                }
            });
        }
        // 注意：initialize 末尾不再无条件 save（审计 1.8 的击穿点已随 ConfigHandle 删除）。
        // 迁移写入由 3→4 自己落盘；load 的结果本来就是最新状态，无需回写。
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void dropAccount(BaseAccount account) {
        Threading.checkMinecraftClientThread();

        if (account.getAccountType() == AccountType.ENV_DEFAULT) {
            return;
        }

        // drop 之后仓储的 current 会落回默认账号，所以要先把「删的是不是当前账号」问出来。
        boolean wasCurrent = repository().getCurrent() == account;
        repository().drop(account);
        signOut(List.of(account));

        if (wasCurrent) {
            switchToFallback();
        }
    }

    /**
     * 移除认证服务器，并连带删除属于它的账号。
     *
     * <p>服务器条目是账号的登录端点、公钥与 skinDomains 的来源；条目删掉后这些账号既登录不了
     * 也刷新不了，留着只会在列表里占位。当前账号在其中时回落到默认账号（与单条删除同一链路）。</p>
     */
    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void removeServer(AuthServer server) {
        Threading.checkMinecraftClientThread();

        if (server == null) {
            return;
        }

        // 账号对象在 drop 之后就不在列表里了，撤销令牌要用，先取一份。
        List<BaseAccount> affected = repository().accountsOn(server.apiRoot());
        repository().authServers().remove(server.kind(), server.apiRoot());
        if (repository().dropAccountsOn(server.apiRoot())) {
            switchToFallback();
        }
        signOut(affected);
    }

    /**
     * 删除账号后尽力让服务端作废令牌（B13）。
     *
     * <p>投到串行通道：删除本身是客户端线程上的即时操作，撤销是收尾——不能让它阻塞界面，
     * 也不能让网络失败影响删除结果。provider 自己吞掉所有异常（见
     * {@link AccountProvider#signOut}）。</p>
     */
    private static void signOut(List<BaseAccount> accounts) {
        for (BaseAccount account : accounts) {
            TaskScheduler.submit(() -> AccountProvider.getProvider(account).signOut(account));
        }
    }

    /** 当前账号被删后回落到默认账号：worker 上登录，客户端线程上切会话（与点击列表项同一条链路）。 */
    private static void switchToFallback() {
        BaseAccount fallback = repository().getCurrent();
        if (fallback == null) {
            // 没有环境账号可回落：只留下列表，游戏会话维持原样。
            return;
        }

        TaskScheduler.submit(() -> {
            AccountSession session = loginAccount(fallback);
            Platforms.getMinecraftPlatform().execute(() -> switchAccount(fallback, session));
        });
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void addAccount(BaseAccount account) {
        Threading.checkMinecraftClientThread();

        repository().add(account);
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void moveAccount(BaseAccount account, int index) {
        Threading.checkMinecraftClientThread();

        repository().move(account, index);
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static AccountSession loginAccount(BaseAccount account) throws IOException {
        Threading.checkAccountWorkerThread();

        if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
            // 刷新成功后 refreshAccount 自己会把 storage 变更落盘，这里不再重复 touch。
            refreshAccount(account, true);

            // 等待在途刷新时，对方可能已经失败；此时不能再拿旧令牌去构建会话。
            if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
                throw new IOException("Account " + account.getAccountType() + " is still unauthorized after refresh");
            }
        }

        AccountContext context = AccountProvider.getProvider(account).createAccountContext(account);

        // 必须在构建会话之前同步：mixin 在会话内随时可能被调用，晚一步就会继续伪造档案密钥对。
        InjectorProfileKeys.setSupported(context.server().profileKeySupported());

        return Platforms.authlibBridge().createAccountProfile(
                account.getAccountStorage(),
                context,
                Platforms.getMinecraftPlatform().getGameProxy()
        );
    }

    /**
     * 切换当前账号：worker 上登录，客户端线程上切会话，随后在客户端线程回调 {@code onSwitched}。
     *
     * <p>已经是当前账号时不重复登录，直接回调，调用方无需自己判断。</p>
     */
    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void selectAccount(BaseAccount account, Runnable onSwitched) {
        Threading.checkMinecraftClientThread();

        if (account == null) {
            return;
        }

        if (repository().getCurrent() == account) {
            onSwitched.run();
            return;
        }

        TaskScheduler.submit(() -> {
            // 连点两下会排队两次；第二段到这里时已经切完了，不必再登录一次。
            if (repository().getCurrent() == account) {
                Platforms.getMinecraftPlatform().execute(onSwitched);
                return;
            }

            AccountSession session = loginAccount(account);
            Platforms.getMinecraftPlatform().execute(() -> {
                switchAccount(account, session);
                onSwitched.run();
            });
        });
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void switchAccount(BaseAccount account, AccountSession session) {
        Threading.checkMinecraftClientThread();

        repository().setCurrent(account);
        Platforms.getMinecraftPlatform().switchAccount(session);
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static void refreshAccount(BaseAccount account, boolean thrown) throws IOException {
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> inFlight = PENDING_REFRESHES.putIfAbsent(account, mine);
        if (inFlight != null) {
            // 已有同账号刷新在跑：等它的结果，不重复轮换令牌。
            awaitPendingRefresh(inFlight, thrown);
            return;
        }

        try {
            doRefreshAccount(account, thrown);
        } finally {
            PENDING_REFRESHES.remove(account, mine);
            mine.complete(null);
        }
    }

    /**
     * 等待同一账号的在途刷新。
     *
     * <p>完成信号只表示「那次刷新已经结束」，不含失败信息：失败已经由在途那一次自己处理
     * （落状态、打日志，或在 {@code thrown} 时抛给它的调用方）。这里等完后由调用方重新检查
     * 账号状态决定下一步，避免把两个调用方的错误处理纠缠在一起。</p>
     */
    private static void awaitPendingRefresh(CompletableFuture<Void> pending, boolean thrown) throws IOException {
        try {
            pending.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (thrown) {
                throw new IOException("Interrupted while waiting for an in-flight refresh", e);
            }
        } catch (ExecutionException e) {
            // 完成信号从不以异常结束；出现即说明内部 bug，记日志而不是静默吞掉。
            AccountsX.LOGGER.warn("In-flight refresh ended exceptionally.", e.getCause());
        }
    }

    /**
     * 问 provider「现有令牌还能直接复用吗」（B4）。
     *
     * <p>任何异常都按「不能复用」处理：判定失败退回刷新，虽然多换一次令牌，但绝不会因为一次
     * 探测失败就把账号留在未授权状态。</p>
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static boolean isTokenReusable(BaseAccount account) {
        try {
            return AccountProvider.getProvider(account).canReuseToken(account);
        } catch (IOException | RuntimeException e) {
            AccountsX.LOGGER.debug("Cannot check whether the token can be reused; refreshing instead.", e);
            return false;
        }
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static void doRefreshAccount(BaseAccount account, boolean thrown) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            markUnauthorizedUnlessAlreadyAuthorized(account);
            if (thrown) {
                throw new IOException("Interrupted");
            } else {
                return;
            }
        }
        if (isTokenReusable(account)) {
            // 令牌仍有效：不刷新。refresh 会作废旧令牌（Yggdrasil 刷新即递增版本号），
            // 而账号 state 是 transient 的、每次启动都按未授权处理——无条件刷新等于每启动
            // 一次换一次令牌，同一账号在别的客户端/实例上就会被顶掉（B4/B14）。
            account.setProfileState(AccountState.AUTHORIZED);
            return;
        }

        account.setProfileState(AccountState.AUTHORIZING);
        try {
            AccountProvider.getProvider(account).refresh(account);
        } catch (IOException e) {
            markUnauthorizedUnlessAlreadyAuthorized(account);
            if (thrown) {
                throw e;
            } else {
                AccountsX.LOGGER.error("Cannot refresh the account.", e);
                return;
            }
        } catch (RuntimeException e) {
            // 服务端返回畸形 JSON（例如缺 accessToken 的 500 响应）会以未检查异常冒出来。
            // 状态机只认 IOException 分支，若不在这里归位，账号会永远停在 AUTHORIZING
            // （界面显示「登录中」，用户既点不动也删不掉，只能重启）。
            markUnauthorizedUnlessAlreadyAuthorized(account);
            if (thrown) {
                throw e;
            } else {
                AccountsX.LOGGER.error("Cannot refresh the account (unexpected error).", e);
                return;
            }
        }

        if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
            throw new IOException("Account provider " + account.getAccountType() + " has finished it's refresh invocation, but neither an exception was thrown nor set the account storage to AUTHORIZED");
        }

        // 刷新会轮换服务端令牌：这一次写入不是「提前落盘」而是必须——下次启动若仍读到旧令牌，
        // 而旧令牌已被服务端作废，账号就再也登录不上（用户报告的「重启后无法登录」）。
        // 仓储把写合并到串行通道，连续刷新的多个账号只会产生一次真实落盘。
        repository().touch(account);
    }

    /**
     * 刷新失败时把账号标记为未授权，但如果它此刻已经是 AUTHORIZED 就保持原状。
     *
     * <p>同一账号的刷新链路上可能有并发的登录/切换，其中一个刚把状态写成 AUTHORIZED，
     * 另一个的失败分支再把状态翻回 UNAUTHORIZED，会让可用账号在 UI 上显示成掉登录。
     * 失败只负责「不要把未知状态当成已授权」，不负责否认别人的成功。</p>
     */
    private static void markUnauthorizedUnlessAlreadyAuthorized(BaseAccount account) {
        if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
            account.setProfileState(AccountState.UNAUTHORIZED);
        }
    }
}
