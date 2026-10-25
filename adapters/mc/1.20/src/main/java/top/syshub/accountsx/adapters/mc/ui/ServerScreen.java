package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.auth.AuthServer;
import top.syshub.accountsx.common.auth.AuthServerKind;
import top.syshub.accountsx.common.auth.AuthServerRegistry;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.utils.Threading;

public class ServerScreen extends Screen {
    private static final int LAYOUT_HORIZONTAL_SPACING = 16;
    private static final int LAYOUT_VERTICAL_SPACING = 32;
    private static final int LAYOUT_BUTTON_H = 20;
    private static final int LAYOUT_TOOL_BAR_W = 150;
    private static final int LAYOUT_ENTRY_H = 36;

    private final Screen parent;
    private AuthServerRegistry registry;

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

        this.addField(new ButtonWidget(
                LAYOUT_HORIZONTAL_SPACING, LAYOUT_VERTICAL_SPACING,
                LAYOUT_TOOL_BAR_W, LAYOUT_BUTTON_H,
                Component.translatable("accountsx.general.action.close"),
                button -> this.onClose())
        );

        // Add server button for yggdrasil
        this.addField(new ButtonWidget(
                LAYOUT_HORIZONTAL_SPACING, LAYOUT_VERTICAL_SPACING + LAYOUT_BUTTON_H + 10,
                LAYOUT_TOOL_BAR_W, LAYOUT_BUTTON_H,
                Component.translatable("accountsx.account.general.add_server"),
                button -> {
                    // TODO: open add server form
                }
        ));
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int y = LAYOUT_VERTICAL_SPACING + 20;
        for (AuthServer server : this.registry.servers()) {
            context.drawString(this.font, server.displayName() != null ? server.displayName() : server.apiRoot(), LAYOUT_HORIZONTAL_SPACING + LAYOUT_TOOL_BAR_W + 20, y, 0xFFFFFF);
            y += LAYOUT_ENTRY_H;
        }

        context.drawCenteredString(this.font, Component.translatable("accountsx.account.general.server_list"), this.width / 2, LAYOUT_VERTICAL_SPACING, 0xFFFFFF);
    }

    public void addField(AbstractWidget drawable) {
        this.addRenderableOnly(drawable);
        this.addWidget(drawable);
    }
}
