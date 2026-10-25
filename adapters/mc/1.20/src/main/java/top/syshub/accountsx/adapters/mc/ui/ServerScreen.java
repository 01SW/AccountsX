package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.auth.AuthServer;
import top.syshub.accountsx.common.auth.AuthServerKind;
import top.syshub.accountsx.common.auth.AuthServerRegistry;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.utils.Threading;

import java.util.List;
import java.util.Map;

public class ServerScreen extends Screen {
    private static final int LAYOUT_HORIZONTAL_SPACING = 16;
    private static final int LAYOUT_VERTICAL_SPACING = 32;
    private static final int LAYOUT_BUTTON_H = 20;
    private static final int LAYOUT_TOOL_BAR_W = 150;
    private static final int LAYOUT_ENTRY_H = 36;

    private final Screen parent;
    private AuthServerRegistry registry;

    // Step 1: URL input
    private EditBox urlInput;
    private Button nextButton;
    // Step 2: confirmation
    private Button finishButton;
    private Button backButton;
    private boolean step2 = false;
    private String probedName = "";
    private String probedUrl = "";
    private String statusMessage = "";

    public ServerScreen(Screen parent) {
        super(Component.translatable("accountsx.account.general.server_list"));
        this.parent = parent;
    }

    public void onClose() {
        assert this.minecraft != null;
        this.minecraft.setScreen(this.parent);
    }

    @Override
    protected void init() {
        super.init();
        this.registry = AccountManager.authServers();

        // Close button
        this.addField(new ButtonWidget(
                LAYOUT_HORIZONTAL_SPACING, LAYOUT_VERTICAL_SPACING,
                LAYOUT_TOOL_BAR_W, LAYOUT_BUTTON_H,
                Component.translatable("accountsx.general.action.close"),
                button -> this.onClose())
        );

        // URL input (step 1)
        this.urlInput = new EditBox(this.font, LAYOUT_HORIZONTAL_SPACING, LAYOUT_VERTICAL_SPACING + LAYOUT_BUTTON_H + 10,
                LAYOUT_TOOL_BAR_W, LAYOUT_BUTTON_H, Component.translatable("accountsx.account.general.add_server"));
        this.urlInput.setHint(Component.translatable("accountsx.account.objects.server_name"));
        this.addWidget(this.urlInput);

        // Next button (step 1)
        this.nextButton = new ButtonWidget(
                LAYOUT_HORIZONTAL_SPACING, LAYOUT_VERTICAL_SPACING + (LAYOUT_BUTTON_H + 10) * 2,
                LAYOUT_TOOL_BAR_W, LAYOUT_BUTTON_H,
                Component.translatable("accountsx.general.action.next", "Next"),
                button -> this.handleNext()
        );
        this.addField(this.nextButton);

        // Back button (step 2, hidden initially)
        this.backButton = new ButtonWidget(
                LAYOUT_HORIZONTAL_SPACING, LAYOUT_VERTICAL_SPACING + (LAYOUT_BUTTON_H + 10) * 2,
                LAYOUT_TOOL_BAR_W, LAYOUT_BUTTON_H,
                Component.translatable("accountsx.general.action.previous", "Back"),
                button -> {
                    this.step2 = false;
                    this.statusMessage = "";
                    this.updateWidgets();
                }
        );
        this.backButton.visible = false;
        this.addField(this.backButton);

        // Finish button (step 2, hidden initially)
        this.finishButton = new ButtonWidget(
                LAYOUT_HORIZONTAL_SPACING, LAYOUT_VERTICAL_SPACING + (LAYOUT_BUTTON_H + 10) * 3,
                LAYOUT_TOOL_BAR_W, LAYOUT_BUTTON_H,
                Component.translatable("accountsx.general.action.finish", "Finish"),
                button -> this.handleFinish()
        );
        this.finishButton.visible = false;
        this.addField(this.finishButton);
    }

    private void handleNext() {
        String url = this.urlInput.getValue().trim();
        if (url.isEmpty()) {
            this.statusMessage = "URL required";
            return;
        }
        this.statusMessage = "Probing...";
        TaskScheduler.submit(() -> {
            try {
                AuthServer probed = this.registry.ensure(AuthServerKind.YGGDRASIL, url, Map.of());
                this.probedName = probed.displayName() != null ? probed.displayName() : url;
                this.probedUrl = probed.apiRoot() != null ? probed.apiRoot() : url;
                this.step2 = true;
                this.statusMessage = "";
            } catch (Exception e) {
                this.statusMessage = "Probe failed: " + e.getMessage();
            }
            // rebuild on client thread
            if (this.minecraft != null) {
                this.minecraft.execute(() -> this.updateWidgets());
            }
        });
    }

    private void handleFinish() {
        this.onClose();
    }

    private void updateWidgets() {
        // Toggle visibility
        this.urlInput.visible = !this.step2;
        this.nextButton.visible = !this.step2;
        this.backButton.visible = this.step2;
        this.finishButton.visible = this.step2;
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int y = LAYOUT_VERTICAL_SPACING + 20;
        // Server list on the right
        for (AuthServer server : this.registry.servers()) {
            context.drawString(this.font, server.displayName() != null ? server.displayName() : server.apiRoot(), LAYOUT_HORIZONTAL_SPACING + LAYOUT_TOOL_BAR_W + 20, y, 0xFFFFFF);
            y += LAYOUT_ENTRY_H;
        }

        // Title
        context.drawCenteredString(this.font, Component.translatable("accountsx.account.general.server_list"), this.width / 2, LAYOUT_VERTICAL_SPACING, 0xFFFFFF);

        // Status message
        if (!this.statusMessage.isEmpty()) {
            context.drawString(this.font, this.statusMessage, LAYOUT_HORIZONTAL_SPACING, LAYOUT_VERTICAL_SPACING + (LAYOUT_BUTTON_H + 10) * 4, 0xFF5555);
        }

        // Step 2 confirmation
        if (this.step2) {
            context.drawString(this.font, "Name: " + this.probedName, LAYOUT_HORIZONTAL_SPACING, LAYOUT_VERTICAL_SPACING + (LAYOUT_BUTTON_H + 10) * 5, 0xFFFFFF);
            context.drawString(this.font, "URL: " + this.probedUrl, LAYOUT_HORIZONTAL_SPACING, LAYOUT_VERTICAL_SPACING + (LAYOUT_BUTTON_H + 10) * 6, 0xFFFFFF);
        }
    }

    public void addField(AbstractWidget drawable) {
        this.addRenderableOnly(drawable);
        this.addWidget(drawable);
    }
}
