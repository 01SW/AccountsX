package top.syshub.accountsx.common.accounts.impl.injector;

import java.io.IOException;

/**
 * 外置登录/刷新失败中「用户可据此操作」的那一类。
 *
 * <p>带一条 i18n key，由 {@code TaskScheduler} 的表单映射翻译成提示；{@code message} 只用于日志，
 * 因此这里只放服务端的 error / errorMessage，绝不放任何令牌（AGENTS.md 的安全约束）。</p>
 */
public class InjectorAuthException extends IOException {
    private final String translationKey;

    public InjectorAuthException(String translationKey, String message) {
        super(message);
        this.translationKey = translationKey;
    }

    public String getTranslationKey() {
        return translationKey;
    }
}
