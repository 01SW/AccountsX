package top.syshub.accountsx.common.ui.form;

import java.util.List;
import java.util.Objects;

/**
 * 声明式表单描述（P3.2）。
 */
public record FormSpec(String titleKey, List<FormField> fields, List<FormAction> actions) {
    public FormSpec {
        Objects.requireNonNull(titleKey, "titleKey");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
    }
}
