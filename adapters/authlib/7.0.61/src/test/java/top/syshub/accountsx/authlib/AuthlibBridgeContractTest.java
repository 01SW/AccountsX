package top.syshub.accountsx.authlib;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

class AuthlibBridgeContractTest {
    @Test
    void reflectionTargetsExist() throws Exception {
        // YggdrasilAuthenticationService must have these fields/methods
        Class<?> yas = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService");
        assertThat(yas).isNotNull();

        // createMinecraftSessionService must exist
        Method cms = yas.getMethod("createMinecraftSessionService");
        assertThat(cms).isNotNull();

        // getServicesKeySet must exist
        Method gsk = yas.getMethod("getServicesKeySet");
        assertThat(gsk).isNotNull();

        // getProxy must exist (inherited from HttpAuthenticationService)
        Method gp = yas.getMethod("getProxy");
        assertThat(gp).isNotNull();

        // YggdrasilMinecraftSessionService must have unpackTextures
        Class<?> ymss = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService");
        Method ut = ymss.getMethod("unpackTextures", Class.forName("com.mojang.authlib.properties.Property"));
        assertThat(ut).isNotNull();

        // getPropertySignatureState must exist (private, but findable)
        Method gspss = null;
        try {
            gspss = ymss.getDeclaredMethod("getPropertySignatureState",
                    Class.forName("com.mojang.authlib.properties.Property"));
        } catch (NoSuchMethodException e) {
            // try alternative
        }
        assertThat(gspss).isNotNull();

        // Environment record must have 4-arg constructor (sessionHost, servicesHost, profilesHost, name)
        Class<?> env = Class.forName("com.mojang.authlib.Environment");
        assertThat(env.isRecord()).isTrue();
        assertThat(env.getRecordComponents()).hasSize(4);
    }

    @Test
    void environmentShapes() throws Exception {
        Class<?> env = Class.forName("com.mojang.authlib.Environment");
        // 4 record components: sessionHost, servicesHost, profilesHost, name
        assertThat(env.getRecordComponents()[0].getName()).isEqualTo("sessionHost");
        assertThat(env.getRecordComponents()[3].getName()).isEqualTo("name");
    }
}
