package top.syshub.accountsx.common.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 认证服务器不响应时必须在有限时间内失败。
 *
 * <p>没有超时会怎样：刷新任务永久占住串行通道，启动批次的 {@code runParallel} 不回调，
 * 账号一直显示「正在登录」，后续同一账号的刷新还会被去重逻辑挂在这条永不结束的请求上。</p>
 */
class JdkHttpGatewayTimeoutTest {

    @Test
    void request_isAbortedAfterTimeout() throws Exception {
        // 只 accept 不响应：模拟「连上了但服务端不回」的认证服务器。
        ServerSocket server = new ServerSocket(0);
        Thread acceptor = new Thread(() -> {
            try (Socket ignored = server.accept()) {
                Thread.sleep(2_000);
            } catch (IOException | InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "timeout-test-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();

        try {
            JdkHttpGateway gateway = new JdkHttpGateway(Duration.ofMillis(300));
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/slow";

            assertThatThrownBy(() -> gateway.get(url))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("timed out");
        } finally {
            server.close();
        }
    }
}
