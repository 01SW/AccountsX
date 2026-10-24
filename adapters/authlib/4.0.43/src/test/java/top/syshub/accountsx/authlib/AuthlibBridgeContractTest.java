package top.syshub.accountsx.authlib;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AuthlibBridgeContractTest {
    @Test
    void reflectionTargetsExist() throws Exception {
        Class<?> yas = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService");
        assertThat(yas).isNotNull();

        // v4 has getTextures(GameProfile, boolean)
        Class<?> ymss = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService");
        Method gt = ymss.getMethod("getTextures",
                Class.forName("com.mojang.authlib.GameProfile"),
                boolean.class);
        assertThat(gt).isNotNull();

        // Environment has 5-arg static create (v4)
        Class<?> env = Class.forName("com.mojang.authlib.Environment");
        Method create = env.getMethod("create", String.class, String.class, String.class, String.class, String.class);
        assertThat(create).isNotNull();
        assertThat(create.getModifiers() & java.lang.reflect.Modifier.STATIC).isNotZero();

        // createMinecraftSessionService
        Method cms = yas.getMethod("createMinecraftSessionService");
        assertThat(cms).isNotNull();
    }

    @Test
    void environmentShape() throws Exception {
        Class<?> env = Class.forName("com.mojang.authlib.Environment");
        assertThat(env.isInterface()).isTrue();
    }
}
