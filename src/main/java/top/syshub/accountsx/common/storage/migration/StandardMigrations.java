package top.syshub.accountsx.common.storage.migration;

import com.google.gson.Gson;

import java.util.List;

/**
 * 生产注册表：0→1→2→3→4 全部跳（P2.3）。
 * {@code gson} 与存储层用同一个实例（{@code NetworkUtils.GSON}），
 * 保证读写行为一致。接线见 {@code JsonConfigStore.createDefault} 与 P2.4。
 */
public final class StandardMigrations {
    private StandardMigrations() {
    }

    public static MigrationChain chain(Gson gson) {
        return new MigrationChain(List.of(
                new InjectorSafety(),
                new RenameAccountType(),
                new SecurityStorage(gson),
                new UnifyAndDecouple(gson)
        ));
    }
}
