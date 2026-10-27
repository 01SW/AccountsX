package top.syshub.accountsx.common.manager;

import top.syshub.accountsx.common.AccountsX;
import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountState;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.adapters.api.AccountSession;
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

public final class AccountManager {
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
            for (BaseAccount account : toRefresh) {
                refreshTasks.add(() -> refreshAccount(account, false));
            }
            TaskScheduler.runParallel(refreshTasks).whenComplete((ignored, t) -> {
                if (t != null) {
                    AccountsX.LOGGER.warn("Some accounts failed to refresh during startup.", t);
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

        if (!wasCurrent) {
            return;
        }

        BaseAccount fallback = repository().getCurrent();
        if (fallback == null) {
            // 没有环境账号可回落：只留下列表，游戏会话维持原样。
            return;
        }

        // 与点击列表项走同一条链路：worker 上登录，客户端线程上切会话。
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
            refreshAccount(account, true);

            repository().touch(account);
        }

        return Platforms.authlibBridge().createAccountProfile(
                account.getAccountStorage(),
                AccountProvider.getProvider(account).createAccountContext(account),
                Platforms.getMinecraftPlatform().getGameProxy()
        );
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void switchAccount(BaseAccount account, AccountSession session) {
        Threading.checkMinecraftClientThread();

        repository().setCurrent(account);
        Platforms.getMinecraftPlatform().switchAccount(session);
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static void refreshAccount(BaseAccount account, boolean thrown) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            account.setProfileState(AccountState.UNAUTHORIZED);
            if (thrown) {
                throw new IOException("Interrupted");
            } else {
                return;
            }
        }
        account.setProfileState(AccountState.AUTHORIZING);
        try {
            AccountProvider.getProvider(account).refresh(account);
        } catch (IOException e) {
            account.setProfileState(AccountState.UNAUTHORIZED);
            if (thrown) {
                throw e;
            } else {
                AccountsX.LOGGER.error("Cannot refresh the account.", e);
                return;
            }
        }

        if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
            throw new IOException("Account provider " + account.getAccountType() + " has finished it's refresh invocation, but neither an exception was thrown nor set the account storage to AUTHORIZED");
        }
    }
}
