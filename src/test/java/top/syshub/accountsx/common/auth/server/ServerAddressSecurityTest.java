package top.syshub.accountsx.common.auth.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 只认显式写出的 http://；https、缺省协议与空值都不是明文。 */
class ServerAddressSecurityTest {

    @Test
    void explicitHttpIsPlaintext() {
        assertThat(ServerAddressSecurity.isPlaintext("http://injector.test/api/yggdrasil")).isTrue();
        assertThat(ServerAddressSecurity.isPlaintext("  HTTP://injector.test  ")).isTrue();
    }

    @Test
    void httpsAndBareHostAreNotPlaintext() {
        assertThat(ServerAddressSecurity.isPlaintext("https://injector.test/api/yggdrasil")).isFalse();
        assertThat(ServerAddressSecurity.isPlaintext("injector.test/api/yggdrasil")).isFalse();
        assertThat(ServerAddressSecurity.isPlaintext("")).isFalse();
        assertThat(ServerAddressSecurity.isPlaintext(null)).isFalse();
    }
}
