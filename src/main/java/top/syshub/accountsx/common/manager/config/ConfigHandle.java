package top.syshub.accountsx.common.manager.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.common.AccountsX;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.utils.NetworkUtils;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ConfigHandle {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigHandle.class);

    private ConfigHandle() {}

    private static String id;

    private static final String CONFIG_LOCATION = "accountsx/accounts.json";

    /**
     * 实例配置的序列化载体：仅含 version 与 id，由 Gson 通过 record 访问器反射读写。
     * 账号载荷本身另存于 {@code ~/.accountsx/<id>.json}，不在此处。
     */
    private record Config(int version, String id) {
        static final int CURRENT_VERSION = ConfigVersion.VALUES[ConfigVersion.VALUES.length - 1].getVersion();
    }

    /**
     * 写入一份默认实例配置（version + id）并落盘空的账号载荷。
     * 配置文件缺失或被非法占用（非普通文件）时调用，副作用与 {@link #write()} 保持一致。
     */
    private static void createDefaultConfig(Path configFile) throws IOException {
        if (id == null)
            id = UUID.randomUUID().toString();
        writeAccounts(id, NetworkUtils.GSON.toJson(List.of()));
        Files.writeString(configFile, NetworkUtils.GSON.toJson(new Config(Config.CURRENT_VERSION, id)));
    }

    public static List<? extends BaseAccount> load() {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_LOCATION);

        try {
            if (!Files.exists(configFile)) {
                Files.createDirectories(configFile.getParent());
                createDefaultConfig(configFile);
                return List.of();
            }

            if (!Files.isRegularFile(configFile)) {
                Files.delete(configFile);
                createDefaultConfig(configFile);
                return List.of();
            }

            JsonElement data;
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                data = NetworkUtils.GSON.fromJson(reader, JsonElement.class);
            }

            if (data instanceof JsonObject jo) {
                if (jo.get("version") instanceof JsonPrimitive versionJP && versionJP.isNumber()) {
                    int configVersion = versionJP.getAsNumber().intValue();

                    for (ConfigVersion value : ConfigVersion.VALUES) {
                        if (configVersion < value.getVersion()) {
                            value.upgrade(jo);
                        }
                    }

                    id = jo.get("id").getAsString();
                    try {
                        UUID.fromString(id);
                    } catch (Exception e) {
                        id = UUID.randomUUID().toString();
                    }
                    return getAccounts();
                }
            }

            throw new IllegalStateException("Illegal config.");
        } catch (Throwable t) {
            AccountsX.LOGGER.warn("Cannot load the config file.", t);
            return List.of();
        }
    }

    public static void write() throws IOException {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_LOCATION);

        List<BaseAccount> accounts = new ArrayList<>();

        for (BaseAccount account : AccountManager.getAccountsView()) {
            if (account.getAccountType() != AccountType.ENV_DEFAULT) {
                accounts.add(account);
            }
        }

        if (id == null)
            id = UUID.randomUUID().toString();
        writeAccounts(id, NetworkUtils.GSON.toJson(accounts));

        try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
            NetworkUtils.GSON.toJson(new Config(Config.CURRENT_VERSION, id), writer);
        }
    }

    private static List<? extends BaseAccount> getAccounts() {
        String userHome = System.getProperty("user.home");
        Path accountsFile = Path.of(userHome, ".accountsx", id + ".json");

        try {
            if (!Files.exists(accountsFile) || !Files.isRegularFile(accountsFile))
                return List.of();

            try (Reader reader = Files.newBufferedReader(accountsFile, StandardCharsets.UTF_8)) {
                // 逐条解析：单条未知 type（如已移除的账号类型）只跳过该条并记 warn，
                // 不能让一条坏数据抛错导致整个账号集加载失败（否则上层返回空列表后 save 回写会清空用户全部账号）。
                JsonElement root = NetworkUtils.GSON.fromJson(reader, JsonElement.class);
                List<BaseAccount> accounts = new ArrayList<>();
                if (root instanceof JsonArray array) {
                    for (JsonElement element : array) {
                        try {
                            accounts.add(NetworkUtils.GSON.fromJson(element, BaseAccount.class));
                        } catch (RuntimeException e) {
                            LOGGER.warn("跳过一条无法解析的账号（可能是已移除的账号类型），该账号不会被加载。", e);
                        }
                    }
                }
                return accounts;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load accounts file", e);
        }
    }

    static void writeAccounts(String id, String accountString) {
        String userHome = System.getProperty("user.home");
        Path accountsFile = Path.of(userHome, ".accountsx", id + ".json");

        try {
            Files.createDirectories(accountsFile.getParent());
            Files.writeString(accountsFile, accountString);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write accounts file", e);
        }
    }
}
