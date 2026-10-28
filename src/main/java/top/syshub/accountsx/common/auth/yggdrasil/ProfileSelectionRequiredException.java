package top.syshub.accountsx.common.auth.yggdrasil;

import java.io.IOException;
import java.util.List;

/**
 * 账号在服务端有多个角色，而调用方没有提供选择界面时抛出。
 *
 * <p>Yggdrasil 的令牌可以绑到账号下任意一个角色，但绑定必须由用户决定；没有界面可选时唯一正确的
 * 行为是失败并告知调用方，而不是像旧实现那样拿空玩家名去猜。</p>
 */
public class ProfileSelectionRequiredException extends IOException {
    private final transient List<YggdrasilProfiles.Profile> profiles;

    public ProfileSelectionRequiredException(List<YggdrasilProfiles.Profile> profiles) {
        super("Yggdrasil account has " + profiles.size() + " profiles; select one to add.");
        this.profiles = List.copyOf(profiles);
    }

    /** 供调用方自行渲染选择界面。 */
    public List<YggdrasilProfiles.Profile> getProfiles() {
        return profiles;
    }
}
