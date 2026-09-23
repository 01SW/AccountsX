package top.syshub.accountsx.common.storage.migration;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.common.storage.ConfigStore;

import java.util.Comparator;
import java.util.List;

/**
 * 严格链式迁移注册表（P2.3 填入具体跳；本类是存储层与迁移层的接缝）。
 *
 * <p>推进规则：从文件里的 version 出发，逐跳找 {@code fromVersion == 当前版本} 的
 * {@link Migration} 应用，直到 {@link ConfigStore#CURRENT_VERSION}；找不到跳即失败
 * （由存储层备份 + 只读降级）。跑完把 version 回写为 CURRENT——现有实现不回写、
 * 靠 initialize() 的 save() 自愈，中途崩溃会重复迁移产生残留，本链跑完即定版。</p>
 */
public final class MigrationChain {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationChain.class);

    /** 无任何迁移：遇到 version &lt; CURRENT 直接失败（存储层降级），不会误覆写。 */
    public static final MigrationChain EMPTY = new MigrationChain(List.of());

    private final List<Migration> steps;

    public MigrationChain(List<Migration> steps) {
        // 构造期校验单调性，排除 0→1→0 这类会死循环的注册
        for (Migration m : steps) {
            if (m.toVersion() <= m.fromVersion())
                throw new IllegalArgumentException("迁移版本必须递增: " + m.fromVersion() + " -> " + m.toVersion());
        }
        this.steps = List.copyOf(steps);
    }

    /**
     * @throws IllegalStateException 链上有缺口（找不到下一跳）或超过 CURRENT
     * @throws Exception             具体迁移自身抛出的任何异常
     */
    public JsonObject upgrade(JsonObject raw, int fromVersion, MigrationContext ctx) throws Exception {
        if (fromVersion > ConfigStore.CURRENT_VERSION)
            throw new IllegalStateException("配置版本 " + fromVersion + " 高于当前支持的 " + ConfigStore.CURRENT_VERSION);

        int version = fromVersion;
        while (version < ConfigStore.CURRENT_VERSION) {
            final int current = version;
            Migration step = steps.stream()
                    .filter(m -> m.fromVersion() == current)
                    .max(Comparator.comparingInt(Migration::toVersion))   // 同一起点取终点最靠后的跳
                    .orElseThrow(() -> new IllegalStateException("缺少 " + current + " 的下一跳迁移"));
            LOGGER.info("执行配置迁移 {} -> {}", step.fromVersion(), step.toVersion());
            raw = step.apply(raw, ctx);
            version = step.toVersion();
        }
        raw.addProperty("version", ConfigStore.CURRENT_VERSION);
        return raw;
    }
}
