package top.syshub.accountsx.common.ui.form;

import java.util.List;
import java.util.Objects;

/**
 * 声明式表单字段（P3.2）。
 *
 * <p>common 只描述字段形状，渲染与命中判定由适配器/shared source set 完成。</p>
 */
public sealed interface FormField permits FormField.Text, FormField.Choice, FormField.Note {

    record Text(FieldKey key, String labelKey, boolean secret, String placeholderKey) implements FormField {
        public Text {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(labelKey, "labelKey");
        }
    }

    record Choice(FieldKey key, String labelKey, List<ChoiceOption> options) implements FormField {
        public Choice {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(labelKey, "labelKey");
            options = List.copyOf(Objects.requireNonNull(options, "options"));
        }
    }

    record Note(String textKey) implements FormField {
        public Note {
            Objects.requireNonNull(textKey, "textKey");
        }
    }

    record ChoiceOption(String value, String labelKey) {
        public ChoiceOption {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(labelKey, "labelKey");
        }
    }
}
