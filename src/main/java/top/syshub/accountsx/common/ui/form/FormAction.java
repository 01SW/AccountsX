package top.syshub.accountsx.common.ui.form;

import java.util.Objects;

/**
 * 表单动作（P3.2）。
 */
public record FormAction(String id, String labelKey, boolean primary) {
    public FormAction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(labelKey, "labelKey");
    }
}
