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

    public static List<Profile> readProfiles(JsonObject json) {
        JsonElement selectedProfile = json.get("selectedProfile");
        if (selectedProfile != null) {
            JsonObject jo = selectedProfile.getAsJsonObject();
            return List.of(new Profile(jo.get("name").getAsString(), jo.get("id").getAsString()));
        }
        JsonArray availableProfiles = json.get("availableProfiles").getAsJsonArray();
        List<Profile> results = new ArrayList<>(availableProfiles.size());
        for (JsonElement availableProfile : availableProfiles) {
            JsonObject jo = availableProfile.getAsJsonObject();
            results.add(new Profile(jo.get("name").getAsString(), jo.get("id").getAsString()));
        }
        return results;
    }
}
