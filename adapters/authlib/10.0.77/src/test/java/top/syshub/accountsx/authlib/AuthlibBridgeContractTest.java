package top.syshub.accountsx.authlib;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AuthlibBridgeContractTest {
    @Test
    void reflectionTargetsExist() throws Exception {
        Class<?> msds = Class.forName("com.mojang.authlib.services.MinecraftServicesDiscoveryService");
        assertThat(msds).isNotNull();

        Class<?> msss = Class.forName("com.mojang.authlib.services.MinecraftServicesSessionService");
        Method ut = msss.getMethod("unpackTextures", Class.forName("com.mojang.authlib.properties.Property"));
        assertThat(ut).isNotNull();

        Method cms = msds.getMethod("createMinecraftSessionService");
        assertThat(cms).isNotNull();
    }
}
