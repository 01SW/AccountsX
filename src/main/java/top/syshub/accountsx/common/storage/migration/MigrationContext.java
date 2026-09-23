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

    /**
     * 全局配置文件（{@code ~/.config/accountsx.json}，D3 解析结果）。
     * 3→4 的"追加语义"需要读它已有的内容做去重合并，并原子写回迁移结果；
     * 链从存储层 load() 触发时它就是被迁移的文件本身。
     */
    Path globalConfigFile();

    /** 头像缓存目录 {@code ~/.cache/accountsx/avatars/}（D4；3→4 把旧 base64 头像外移到这里）。 */
    Path avatarCacheDir();
}
