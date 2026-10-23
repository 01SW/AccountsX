package top.syshub.accountsx.common.ui.form;

/**
 * 表单校验结果（P3.2）。
 *
 * <p>取代 {@code AccountProvider#validate} 旧的 {@code int} 常量；
 * P3.3 接线时适配器按此结果决定关闭/保留表单。</p>
 */
public enum ValidationOutcome {
    CLOSE_IMMEDIATELY,
    KEEP_OPEN_AND_HANDLE
}
