package top.syshub.accountsx.common.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 网络层抽象（P1.3）。把认证流程对 HTTP 的依赖收敛到这个接口，
 * 使 provider 可在单测中注入假实现（见 {@code JdkHttpGateway} 的生产实现）。
 *
 * <p>所有方法默认校验 HTTP 状态码：非 {@code 2xx} 抛 {@link IOException}，
 * 除非对应方法显式接受 {@code ignoreHttpStatus=true}。</p>
 */
public interface HttpGateway {
    /** GET，校验状态码。 */
    JsonObject get(String url) throws IOException;

    /** GET（带请求头），校验状态码。 */
    JsonObject get(String url, Map<String, String> headers) throws IOException;

    /** POST JSON，校验状态码。 */
    JsonObject postJson(String url, JsonElement body) throws IOException;

    /** POST JSON，可选忽略状态码。 */
    JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) throws IOException;

    /** POST form（application/x-www-form-urlencoded），校验状态码。 */
    JsonObject postForm(String url, Map<String, String> formData) throws IOException;

    /** POST form，可选忽略状态码。 */
    JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) throws IOException;

    /**
     * 只读取响应头、不解析响应体的 GET。
     *
     * <p>与 {@link #get(String)} 不同，本方法不校验状态码：服务器地址探测常见 3xx/4xx，
     * 但响应头里的 {@code X-Authlib-Injector-API-Location} 仍是有效信息。
     * 同时返回跟随重定向后的最终 URL——按启动器技术规范，没有 ALI 头时它就是 API 地址。</p>
     */
    Headers getHeaders(String url) throws IOException;

    /** GET，返回原始响应体字节（用于下载非 JSON 资源，如皮肤 PNG）。校验状态码。 */
    byte[] getBinary(String url) throws IOException;

    /**
     * {@link #getHeaders(String)} 的结果。
     *
     * @param finalUri 跟随重定向后的最终 URL；规范要求把它当作「当前 URL」参与 ALI 解析
     * @param headers  原始响应头（大小写无关查找请用 {@code NetworkUtils.getHeaderIgnoreCase}）
     */
    record Headers(URI finalUri, Map<String, List<String>> headers) {
    }
}
