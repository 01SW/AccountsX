package top.syshub.accountsx.common.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

public final class YggdrasilProfiles {
    private YggdrasilProfiles() {
    }

    public record Profile(String playerName, String playerUuid) {
    }

    /**
     * 读响应里的角色。
     *
     * <p>{@code selectedProfile} 优先；没有时读 {@code availableProfiles}。两者都可能缺席，且缺席是
     * 常态而非异常：Yggdrasil 服务端规范里刷新响应只有 {@code selectedProfile}、没有
     * {@code availableProfiles}，而令牌未绑定角色时 {@code selectedProfile} 也可能为空。因此这里
     * 对缺字段、错类型都返回空列表/跳过该条，不再把 {@code NullPointerException} 抛给调用方
     * （旧实现在缺 {@code availableProfiles} 时直接 NPE，只能靠上层 catch 兜底）。</p>
     */
    public static List<Profile> readProfiles(JsonObject json) {
        Profile selected = toProfile(json.get("selectedProfile"));
        if (selected != null) {
            return List.of(selected);
        }

        JsonElement available = json.get("availableProfiles");
        if (!(available instanceof JsonArray availableProfiles)) {
            return List.of();
        }

        List<Profile> results = new ArrayList<>(availableProfiles.size());
        for (JsonElement element : availableProfiles) {
            Profile profile = toProfile(element);
            if (profile != null) {
                results.add(profile);
            }
        }
        return results;
    }

    /** 单条角色：字段缺失或不是字符串时返回 null（不猜、不抛）。 */
    private static Profile toProfile(JsonElement element) {
        if (!(element instanceof JsonObject jo)) {
            return null;
        }

        String name = string(jo.get("name"));
        String uuid = string(jo.get("id"));
        if (name == null || uuid == null) {
            return null;
        }
        return new Profile(name, uuid);
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString()
                : null;
    }
}
