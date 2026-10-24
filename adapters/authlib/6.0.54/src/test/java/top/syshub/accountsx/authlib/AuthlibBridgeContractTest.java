package top.syshub.accountsx.authlib;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AuthlibBridgeContractTest {
    @Test
    void reflectionTargetsExist() throws Exception {
        Class<?> yas = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService");
        assertThat(yas).isNotNull();

        Class<?> ymss = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService");
        Method ut = ymss.getMethod("unpackTextures", Class.forName("com.mojang.authlib.properties.Property"));
        assertThat(ut).isNotNull();

        Method cms = yas.getMethod("createMinecraftSessionService");
        assertThat(cms).isNotNull();

        Class<?> env = Class.forName("com.mojang.authlib.Environment");
        assertThat(env.isRecord()).isTrue();
        assertThat(env.getRecordComponents()).hasSize(3);
    }
}
