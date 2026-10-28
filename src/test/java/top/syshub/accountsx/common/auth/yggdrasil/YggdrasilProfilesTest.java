package top.syshub.accountsx.common.auth.yggdrasil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 角色解析对缺字段、错类型都必须宽容：刷新响应只有 selectedProfile 是规范行为。 */
class YggdrasilProfilesTest {

    @Test
    void selectedProfileWinsOverAvailableProfiles() {
        JsonObject json = new JsonObject();
        json.add("selectedProfile", profile("Steve", "uuid-steve"));
        json.add("availableProfiles", profiles(profile("Alex", "uuid-alex")));

        assertThat(YggdrasilProfiles.readProfiles(json))
                .containsExactly(new YggdrasilProfiles.Profile("Steve", "uuid-steve"));
    }

    @Test
    void refreshResponseWithoutAvailableProfilesDoesNotThrow() {
        JsonObject json = new JsonObject();
        json.addProperty("accessToken", "fake-access");

        assertThat(YggdrasilProfiles.readProfiles(json)).isEmpty();
    }

    @Test
    void malformedEntriesAreSkipped() {
        JsonObject json = new JsonObject();
        JsonArray available = new JsonArray();
        available.add(profile("Steve", "uuid-steve"));
        available.add(new JsonObject());
        available.add("not-an-object");
        json.add("availableProfiles", available);

        assertThat(YggdrasilProfiles.readProfiles(json))
                .containsExactly(new YggdrasilProfiles.Profile("Steve", "uuid-steve"));
    }

    @Test
    void availableProfilesOfWrongTypeIsEmpty() {
        JsonObject json = new JsonObject();
        json.addProperty("availableProfiles", "not-an-array");

        assertThat(YggdrasilProfiles.readProfiles(json)).isEmpty();
    }

    private static JsonObject profile(String name, String uuid) {
        JsonObject profile = new JsonObject();
        profile.addProperty("name", name);
        profile.addProperty("id", uuid);
        return profile;
    }

    private static JsonArray profiles(JsonObject... entries) {
        JsonArray array = new JsonArray();
        for (JsonObject entry : entries) {
            array.add(entry);
        }
        return array;
    }
}
