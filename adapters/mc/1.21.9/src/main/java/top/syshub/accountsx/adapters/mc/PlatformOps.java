package top.syshub.accountsx.adapters.mc;

import net.minecraft.client.Minecraft;

public class PlatformOps {
    public void openBrowser(String url) {
        net.minecraft.Util.getPlatform().openUri(url);
    }

    public void copyText(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) {
            new com.mojang.blaze3d.platform.ClipboardManager().setClipboard(client.getWindow(), text);
        } else {
            client.schedule(() -> copyText(text));
        }
    }
}
