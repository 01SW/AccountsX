package top.syshub.accountsx.adapters.mc.ui.impl;

import org.jetbrains.annotations.NotNull;
import top.syshub.accountsx.common.AccountsX;
import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.ui.Memory;
import top.syshub.accountsx.common.ui.UIScreen;
import top.syshub.accountsx.common.ui.form.FieldKey;
import top.syshub.accountsx.common.ui.form.FormAction;
import top.syshub.accountsx.common.ui.form.FormField;
import top.syshub.accountsx.common.ui.form.FormSpec;
import top.syshub.accountsx.common.ui.form.ValidationOutcome;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import top.syshub.accountsx.adapters.mc.ui.ButtonWidget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class UIScreenImpl implements UIScreen {
    private final FormSpec form;

    public UIScreenImpl(FormSpec form) {
        this.form = form;
    }
    public static void login(Minecraft client, AccountScreen accountScreen, AccountProvider<?> provider) {
        FormSpec form = provider.form();
        UIScreenImpl screen = new UIScreenImpl(form);

        client.setScreen(screen.bind(accountScreen, provider));
    }

    private static final class ValuedWidget<T extends Renderable> {
        private final String description;

        private T widget;

        public ValuedWidget(String description) {
            this.description = description;
        }
    }

    private boolean readonly = false;

    private String title;

    private final Map<String, ValuedWidget<EditBox>> inputs = new LinkedHashMap<>();
    private final Map<String, EditBox> textInputs = new LinkedHashMap<>();
    private final Map<String, ButtonWidget> choiceButtons = new LinkedHashMap<>();
    private final Map<String, String> choiceValues = new LinkedHashMap<>();

    @Override
    public void setTitle(String description) {
        if (readonly) {
            throw new IllegalStateException("UIScreen has been frozen.");
        }
        this.title = description;
    }

    @Override
    public void putTextInput(String guid, String description) {
        if (readonly) {
            throw new IllegalStateException("UIScreen has been frozen.");
        }
        this.inputs.put(guid, new ValuedWidget<>(description));
    }

    @Override
    public String getTextInput(String guid) {
        if (!readonly) {
            throw new IllegalStateException("UIScreen hasn't been frozen.");
        }
        return this.inputs.get(guid).widget.getValue();
    }

    public Screen bind(AccountScreen parent, AccountProvider<?> provider) {
        readonly = true;

        return new LoginScreen(Component.translatable(form.titleKey()), parent, provider);
    }

    private final class LoginScreen extends Screen {
        private final AccountScreen parent;

        private final AccountProvider<?> provider;

        public LoginScreen(Component title, AccountScreen parent, AccountProvider<?> provider) {
            super(title);
            this.parent = parent;
            this.provider = provider;
        }

        @Override
        public void onClose() {
            assert this.minecraft != null;

            this.minecraft.setScreen(parent);
        }

        @Override
        protected void init() {
            super.init();

            final Minecraft minecraft = this.minecraft;
            if (minecraft == null) {
                return;
            }

            int fieldCount = form.fields().size();
            int actionCount = form.actions().size();
            int rows = fieldCount + (actionCount > 0 ? 1 : 0);
            int widgetsTop = this.height / 2 - rows * 25 / 2;
            int widgetsLeft = this.width / 2 - 100;

            for (FormField field : form.fields()) {
                if (field instanceof FormField.Text text) {
                    EditBox tf = new EditBox(minecraft.font, widgetsLeft, widgetsTop, 200, 20, Component.empty());
                    tf.setMaxLength(128);
                    textInputs.put(text.key().value(), tf);
                    this.addField(tf);
                } else if (field instanceof FormField.Choice choice) {
                    java.util.List<String> values = new java.util.ArrayList<>();
                    java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
                    for (FormField.Choice.ChoiceOption option : choice.options()) {
                        values.add(option.value());
                        labels.put(option.value(), option.labelKey());
                    }

                    String initial = values.isEmpty() ? "" : values.get(0);
                    if (!values.isEmpty()) {
                        choiceValues.put(choice.key().value(), initial);
                    }
                    net.minecraft.network.chat.Component initialLabel = values.isEmpty()
                            ? Component.translatable(choice.labelKey())
                            : Component.translatable(labels.get(initial));
                    ButtonWidget cb = new ButtonWidget(widgetsLeft, widgetsTop, 200, 20, initialLabel,
                            widget -> {
                                String current = choiceValues.getOrDefault(choice.key().value(), initial);
                                int index = values.indexOf(current);
                                String next = values.isEmpty() ? "" : values.get((index + 1) % values.size());
                                if (!values.isEmpty()) {
                                    choiceValues.put(choice.key().value(), next);
                                    widget.setMessage(Component.translatable(labels.get(next)));
                                }
                            });
                    choiceButtons.put(choice.key().value(), cb);
                    this.addField(cb);
                }

                widgetsTop += 25;
            }

            if (!form.actions().isEmpty()) {
                int buttonWidth = fieldCount == 0 ? 100 : 95;
                int totalWidth = form.actions().size() * buttonWidth + (form.actions().size() - 1) * 10;
                int buttonLeft = this.width / 2 - totalWidth / 2;
                int buttonTop = widgetsTop + 5;

                for (FormAction action : form.actions()) {
                    this.addField(new ButtonWidget(buttonLeft, buttonTop, buttonWidth, 20,
                            Component.translatable(action.labelKey()),
                            widget -> handleAction(action, minecraft)));
                    buttonLeft += buttonWidth + 10;
                }
            }
        }

        private void handleAction(FormAction action, Minecraft minecraft) {
            Memory memory = new DefaultMemory(this);

            for (FormField field : form.fields()) {
                if (field instanceof FormField.Text text) {
                    EditBox box = textInputs.get(text.key().value());
                    memory.set(text.key().value(), box == null ? "" : box.getValue());
                } else if (field instanceof FormField.Choice choice) {
                    memory.set(choice.key().value(), choiceValues.getOrDefault(choice.key().value(), ""));
                }
            }

            try {
                ValidationOutcome outcome = provider.validate(memory);
                switch (outcome) {
                    case CLOSE_IMMEDIATELY -> this.onClose();
                    case KEEP_OPEN_AND_HANDLE -> {
                    }
                }
            } catch (IllegalArgumentException e) {
                AccountsX.LOGGER.warn("Invalid account argument.", e);
                return;
            }

            if ("login".equals(action.id())) {
                TaskScheduler.submit(() -> {
                    BaseAccount account;
                    try {
                        account = provider.login(memory);
                    } catch (Throwable t) {
                        minecraft.tell(() -> {
                            if (minecraft.screen == this) {
                                this.onClose();
                            }
                        });

                        throw t;
                    }

                    minecraft.tell(() -> {
                        if (minecraft.screen == this) {
                            this.onClose();
                        }

                        AccountManager.addAccount(account);

                        this.parent.syncAccounts();
                    });
                });
            } else if ("close".equals(action.id())) {
                this.onClose();
            }
        }
        @Override
        public void render(@NotNull GuiGraphics context, int mouseX, int mouseY, float delta) {
            assert this.minecraft != null;

        super.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

            int textTop = this.height / 2 - (form.fields().size() + (form.actions().isEmpty() ? 0 : 1)) * 25 / 2 + 5;
            int textLeft = this.width / 2 - 170;

            Component title = Component.translatable(form.titleKey());
            context.drawString(
                    minecraft.font,
                    title,
                    this.width / 2 - minecraft.font.width(title) / 2,
                    textTop - 40,
                    0xFFFFFF,
                    true
            );

            for (FormField field : form.fields()) {
                Component label = null;
                if (field instanceof FormField.Text text) {
                    label = Component.translatable(text.labelKey());
                } else if (field instanceof FormField.Choice choice) {
                    label = Component.translatable(choice.labelKey());
                } else if (field instanceof FormField.Note note) {
                    label = Component.translatable(note.textKey());
                }

                if (label != null) {
                    context.drawString(
                            minecraft.font,
                            label,
                            textLeft,
                            textTop,
                            0xFFFFFF,
                            true
                    );
                }

                textTop += 25;
            }
        }
        public <T extends AbstractWidget> T addField(T drawable) {
            this.addRenderableOnly(drawable);
            this.addWidget(drawable);
            return drawable;
        }
    }
}
