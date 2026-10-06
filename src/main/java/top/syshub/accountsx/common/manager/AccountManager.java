package top.syshub.accountsx.common.manager;

import top.syshub.accountsx.common.AccountsX;
import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountState;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.adapters.api.AccountSession;
import top.syshub.accountsx.common.manager.config.ConfigHandle;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.utils.Threading;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AccountManager {
    private static final List<BaseAccount> accounts = new CopyOnWriteArrayList<>();
    private static final List<BaseAccount> readonlyAccounts = Collections.unmodifiableList(accounts);
    private static volatile BaseAccount current = null;

    private AccountManager() {
    }

    public static List<BaseAccount> getAccountsView() {
        return readonlyAccounts;
    }

    public static BaseAccount getCurrentAccount() {
        return current;
    }

    public static void initialize() {
        accounts.add(current = Platforms.getMinecraftPlatform().fromCurrentClient());

        accounts.addAll(ConfigHandle.load());

        List<BaseAccount> toRefresh = new ArrayList<>();
        for (BaseAccount account : accounts) {
            if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
                toRefresh.add(account);
            }
        }

        if (!toRefresh.isEmpty()) {
            List<TaskScheduler.Task> refreshTasks = new ArrayList<>(toRefresh.size());
            for (BaseAccount account : toRefresh) {
                refreshTasks.add(() -> refreshAccount(account, false));
            }
            // 写盘必须等这一批刷新结束：刷新会轮换服务端令牌（Yggdrasil 刷新后旧 accessToken 立刻失效，
            // drasl 用版本号作废它），若在刷新完成前就把账号写下去，落盘的是刷新前的旧令牌，
            // 下次启动带着它去刷新必然 403 —— 表现就是「每次重开游戏都要重新添加账号」。
            TaskScheduler.runParallel(refreshTasks).whenComplete((ignored, t) -> {
                if (t != null) {
                    AccountsX.LOGGER.warn("Some accounts failed to refresh during startup.", t);
                }
                save();
            });
        } else {
            save();
        }
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void dropAccount(BaseAccount account) {
        Threading.checkMinecraftClientThread();

        if (account.getAccountType() == AccountType.ENV_DEFAULT) {
            return;
        }

        accounts.remove(account);
        save();
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void addAccount(BaseAccount account) {
        Threading.checkMinecraftClientThread();

        accounts.add(account);
        save();
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void moveAccount(BaseAccount account, int index) {
        Threading.checkMinecraftClientThread();

        accounts.remove(account);
        accounts.add(index, account);
        save();
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static AccountSession loginAccount(BaseAccount account) throws IOException {
        Threading.checkAccountWorkerThread();

        if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
            refreshAccount(account, true);

            // 刷新会轮换服务端令牌（旧 accessToken 随即失效），必须立刻落盘，
            // 否则下次启动会拿着已作废的令牌去刷新。
            save();
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

        current = account;
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
        } catch (RuntimeException e) {
            // 服务端返回畸形 JSON 等情况会以未检查异常冒出来（如 accessToken 缺失导致 NPE）。
            // 状态机只认 IOException，若不在这里归位，账号会永远停在 AUTHORIZING（界面显示「登录中」）。
            account.setProfileState(AccountState.UNAUTHORIZED);
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
    }

    private static void save() {
        TaskScheduler.submit(ConfigHandle::write);
    }
}
