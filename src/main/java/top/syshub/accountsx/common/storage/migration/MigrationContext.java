package top.syshub.accountsx.common.storage.migration;

import java.nio.file.FileSystem;
import java.nio.file.Path;

/**
 * 迁移执行环境：文件系统经注入，具体迁移（如 3→4 读旧双文件结构）可全程在
 * jimfs 内存文件系统中测试，不碰真实磁盘。
 */
public interface MigrationContext {
    FileSystem fs();

    /** 旧实例配置目录 {@code <game>/config/accountsx/}（读 version+id 索引用）。 */
    Path legacyGameConfigDir();

    /** 旧载荷目录 {@code ~/.accountsx/}。 */
    Path legacyHomeDir();
}
