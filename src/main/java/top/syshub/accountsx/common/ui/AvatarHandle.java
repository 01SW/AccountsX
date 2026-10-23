package top.syshub.accountsx.common.ui;

import java.util.Objects;

/**
 * UI 层可直接渲染的头像句柄（P3.2）。
 *
 * <p>common 不持有纹理对象，只暴露内容哈希 key 与缓存时间戳；
 * 适配器按 key 从 {@code AvatarCache} 加载对应 PNG。</p>
 */
public record AvatarHandle(String key, long cachedAt) {
    public AvatarHandle {
        Objects.requireNonNull(key, "key");
    }
}
