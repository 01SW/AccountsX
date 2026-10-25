package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.auth.AuthServer;
import top.syshub.accountsx.common.auth.AuthServerKind;
import top.syshub.accountsx.common.auth.AuthServerRegistry;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.utils.Threading;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ServerScreen extends Screen {
    private static final int LEFT_W = 160;
    private static final int BTN_H = 20;
    private static final int BTN_GAP = 4;
    private static final int PAD = 8;

    private final Screen parent;
    private AuthServerRegistry registry;

    // Filter state: 0=All, 1=Microsoft, 2..=external servers, last=Offline
    private int selectedFilter = 0;
    private List<FilterEntry> filters = new ArrayList<>();

    // Add server dialog state
    private boolean addingServer = false;
    private EditBox urlInput;
    private Button nextBtn;
    private Button backBtn;
    private Button finishBtn;
    private String probedName = "";
    private String probedUrl = "";
    private String statusMsg = "";

    // Account list
    private List<BaseAccount> filteredAccounts = new ArrayList<>();

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
        this.rebuildFilters();
        this.updateFilteredAccounts();
        this.updateWidgets();
    }

    private void rebuildFilters() {
        this.filters.clear();
        this.filters.add(new FilterEntry("accountsx.account.general.all_accounts", null, FilterType.ALL));
        this.filters.add(new FilterEntry("accountsx.account.type.microsoft.name", null, FilterType.MICROSOFT));
        for (AuthServer s : this.registry.servers()) {
            if (s.kind() == AuthServerKind.YGGDRASIL) {
                this.filters.add(new FilterEntry(s.displayName() != null ? s.displayName() : s.apiRoot(), s, FilterType.EXTERNAL));
            }
        }
        this.filters.add(new FilterEntry("accountsx.account.type.offline.name", null, FilterType.OFFLINE));
    }

    private void updateFilteredAccounts() {
        this.filteredAccounts.clear();
        FilterEntry f = this.filters.get(this.selectedFilter);
        for (BaseAccount acc : AccountManager.getAccountsView()) {
            AccountType t = acc.getAccountType();
            switch (f.type) {
                case ALL:
                    this.filteredAccounts.add(acc);
                    break;
                case MICROSOFT:
                    if (t == AccountType.MICROSOFT) this.filteredAccounts.add(acc);
                    break;
                case OFFLINE:
                    if (t == AccountType.OFFLINE) this.filteredAccounts.add(acc);
                    break;
                case EXTERNAL:
                    // external accounts: match by server
                    if (t == AccountType.AUTHLIB_INJECTOR || t == AccountType.UNITED_INJECTOR) {
                        this.filteredAccounts.add(acc);
                    }
                    break;
            }
        }
    }

    private void updateWidgets() {
        this.clearWidgets();

        int x = PAD;
        int y = PAD + 20;

        // Back button
        this.addField(new ButtonWidget(x, y, LEFT_W - PAD * 2, BTN_H,
                Component.translatable("accountsx.general.action.close"),
                button -> this.onClose()));
        y += BTN_H + BTN_GAP * 2;

        // Filter buttons
        for (int i = 0; i < this.filters.size(); i++) {
            final int idx = i;
            FilterEntry f = this.filters.get(i);
            String prefix = (i == this.selectedFilter) ? "§a> " : "  ";
            this.addField(new ButtonWidget(x, y, LEFT_W - PAD * 2, BTN_H,
                    Component.literal(prefix + f.label),
                    button -> {
                        this.selectedFilter = idx;
                        this.updateFilteredAccounts();
                        this.updateWidgets();
                    }));
            y += BTN_H + BTN_GAP;
        }

        y += BTN_GAP;

        // Add Auth Server button
        this.addField(new ButtonWidget(x, y, LEFT_W - PAD * 2, BTN_H,
                Component.translatable("accountsx.account.general.add_server"),
                button -> {
                    this.addingServer = true;
                    this.updateWidgets();
                }));

        // Right panel: Add Account button at bottom (hidden when All selected)
        if (this.selectedFilter != 0) {
            int rx = LEFT_W + PAD;
            int ry = this.height - PAD - BTN_H;
            this.addField(new ButtonWidget(rx, ry, this.width - LEFT_W - PAD * 2, BTN_H,
                    Component.translatable("accountsx.account.general.add_account", "Add Account"),
                    button -> {
                        // TODO: open account creation for selected filter
                    }));
        }

        // Add server dialog widgets
        if (this.addingServer) {
            int cx = this.width / 2 - 150;
            int cy = this.height / 2 - 60;
            this.urlInput = new EditBox(this.font, cx, cy, 300, BTN_H, Component.literal("URL"));
            this.urlInput.setHint(Component.literal("https://..."));
            this.addWidget(this.urlInput);

            this.nextBtn = new ButtonWidget(cx, cy + BTN_H + 8, 140, BTN_H,
                    Component.translatable("accountsx.general.action.next", "Next"),
                    button -> this.handleNext());
            this.addField(this.nextBtn);

            this.backBtn = new ButtonWidget(cx, cy + BTN_H + 8, 140, BTN_H,
                    Component.translatable("accountsx.general.action.previous", "Back"),
                    button -> {
                        this.addingServer = false;
                        this.statusMsg = "";
                        this.updateWidgets();
                    });
            this.backBtn.visible = false;
            this.addField(this.backBtn);

            this.finishBtn = new ButtonWidget(cx + 160, cy + BTN_H + 8, 140, BTN_H,
                    Component.translatable("accountsx.general.action.finish", "Finish"),
                    button -> {
                        this.addingServer = false;
                        this.statusMsg = "";
                        this.rebuildFilters();
                        this.updateWidgets();
                    });
            this.finishBtn.visible = false;
            this.addField(this.finishBtn);
        }
    }

    private void handleNext() {
        if (this.urlInput == null) return;
        String url = this.urlInput.getValue().trim();
        if (url.isEmpty()) {
            this.statusMsg = "URL required";
            return;
        }
        this.statusMsg = "Probing...";
        TaskScheduler.submit(() -> {
            try {
                AuthServer probed = this.registry.ensure(AuthServerKind.YGGDRASIL, url, Map.of());
                this.probedName = probed.displayName() != null ? probed.displayName() : url;
                this.probedUrl = probed.apiRoot() != null ? probed.apiRoot() : url;
                this.addingServer = true;
                // show step 2
                if (this.urlInput != null) this.urlInput.visible = false;
                if (this.nextBtn != null) this.nextBtn.visible = false;
                if (this.backBtn != null) this.backBtn.visible = true;
                if (this.finishBtn != null) this.finishBtn.visible = true;
                this.statusMsg = "";
            } catch (Exception e) {
                this.statusMsg = "Probe failed: " + e.getMessage();
            }
            if (this.minecraft != null) {
                this.minecraft.execute(() -> this.updateWidgets());
            }
        });
    }

    private void addField(AbstractWidget w) {
        this.addRenderableWidget(w);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        // Draw left panel background
        context.fill(0, 0, LEFT_W, this.height, 0x80000000);

        // Draw right panel: account list
        int rx = LEFT_W + PAD;
        int ry = PAD + 20;
        context.drawString(this.font, this.filters.get(this.selectedFilter).label, rx, ry, 0xFFFFFF);
        ry += 20;

        for (BaseAccount acc : this.filteredAccounts) {
            context.drawString(this.font, acc.getAccountName(), rx, ry, 0xAAAAAA);
            ry += 12;
        }

        // Status message
        if (!this.statusMsg.isEmpty()) {
            context.drawString(this.font, this.statusMsg, this.width / 2 - 100, this.height / 2 + 40, 0xFF5555);
        }

        // Add server dialog overlay
        if (this.addingServer && this.urlInput == null) {
            // initial state before handleNext sets up widgets
        }

        // Probed info in step 2
        if (this.addingServer && this.backBtn != null && this.backBtn.visible) {
            int cx = this.width / 2 - 150;
            int cy = this.height / 2 - 60;
            context.drawString(this.font, "Name: " + this.probedName, cx, cy + 30, 0xFFFFFF);
            context.drawString(this.font, "URL: " + this.probedUrl, cx, cy + 42, 0xFFFFFF);
        }
    }

    // Helper classes
    private enum FilterType { ALL, MICROSOFT, EXTERNAL, OFFLINE }
    private static class FilterEntry {
        final String label;
        final AuthServer server;
        final FilterType type;
        FilterEntry(String label, AuthServer server, FilterType type) {
            this.label = label;
            this.server = server;
            this.type = type;
        }
    }
}
