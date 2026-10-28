package top.syshub.accountsx.common.manager;

import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.impl.offline.OfflineAccount;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号列表副标题的来源判定：只有外置账号有「服务器名」，其余一律回落类型名。
 *
 * <p>这条判定在客户端线程逐帧调用，因此非外置账号必须在接触仓储之前就返回，
 * 否则未初始化（或初始化失败）的仓储会让整个账号界面崩掉。</p>
 */
class AccountManagerServerNameTest {

    @Test
    void serverDisplayName_offlineAccount_isNull() {
        OfflineAccount account = new OfflineAccount("fake-token", "Alex", UUID.randomUUID());

        assertThat(AccountManager.serverDisplayName(account)).isNull();
    }

    @Test
    void serverDisplayName_nullAccount_isNull() {
        assertThat(AccountManager.serverDisplayName(null)).isNull();
    }
}
