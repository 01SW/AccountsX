package top.syshub.accountsx.adapters.mc;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.ClipboardManager;

public class PlatformOps {
    public void openBrowser(String url) {
        com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(url));
    }

    public void copyText(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) {
            new ClipboardManager().setClipboard(text);
        } else {
            client.schedule(() -> copyText(text));
        }
    }
}
