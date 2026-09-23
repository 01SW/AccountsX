package top.syshub.accountsx.common.storage.migration;

import com.google.gson.JsonObject;

/**
 * 单跳迁移 {@code fromVersion → toVersion}（接口形状见 REFACTORING/02-target-architecture.md）。
 *
 * <p>P2.3 落地具体实现（含 3→4 {@code UNIFY_AND_DECOUPLE}，方案 C）。
 * 约定：实现负责把 {@code version} 写为 {@link #toVersion()}，
 * {@link MigrationChain} 只负责按版本推进与最终校验。</p>
 */
public interface Migration {
    int fromVersion();

    int toVersion();

    /**
     * @param config 当前原始 JSON（version == fromVersion()）
     * @param ctx    旧文件位置与文件系统，让迁移可在内存文件系统里测试
     * @return 升级后的 JSON（version == toVersion()）
     * @throws Exception 任何失败都中止整条链，由存储层备份原文件并进入只读降级，绝不静默清空
     */
    JsonObject apply(JsonObject config, MigrationContext ctx) throws Exception;
}
