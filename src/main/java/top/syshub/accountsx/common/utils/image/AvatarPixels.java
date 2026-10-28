package top.syshub.accountsx.common.utils.image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/**
 * 把缓存的头像 PNG 解码成可以直接用矩形填充画出来的像素网格。
 *
 * <p>为什么不用纹理：{@code GuiGraphics.blit} 与 {@code DynamicTexture} 的签名在 1.20 / 1.21.x /
 * 26.x 三代之间各不相同，而登录界面只有 1.20 与 26.x 两份源码、其余版本靠源码回退共享，
 * 一旦写死其中一个签名就会让继承链上的版本全部编译失败。{@code fill} 是唯一三代一致的绘制原语，
 * 所以这里把解码放在 common（AWT 已在 {@code utils/image} 白名单放行），界面侧只画色块。</p>
 *
 * <p>缓存的 PNG 是渲染器产出的 32×32 头像，这里按需降采样到 {@code size×size}；同一张头像只解码一次，
 * 由调用方缓存结果。</p>
 */
public final class AvatarPixels {

    private AvatarPixels() {
    }

    /**
     * 解码 PNG 并降采样成 {@code size×size} 的 ARGB 网格（行优先）。
     *
     * @return 长度 {@code size*size} 的数组，完全透明的像素为 0；无法解码时返回 {@code null}
     */
    public static int[] decode(byte[] png, int size) {
        if (png == null || png.length == 0 || size <= 0) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return null;
            }
            return sample(image, size);
        } catch (IOException e) {
            // 头像是装饰：坏掉的缓存不该拦住登录，下次重画再试。
            return null;
        }
    }

    private static int[] sample(BufferedImage image, int size) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] out = new int[size * size];
        Arrays.fill(out, 0);
        for (int y = 0; y < size; y++) {
            int y0 = y * height / size;
            int y1 = Math.max(y0 + 1, (y + 1) * height / size);
            for (int x = 0; x < size; x++) {
                int x0 = x * width / size;
                int x1 = Math.max(x0 + 1, (x + 1) * width / size);
                out[y * size + x] = average(image, x0, y0, x1, y1);
            }
        }
        return out;
    }

    /** 按 alpha 加权平均，避免透明边缘把周围像素拉黑。 */
    private static int average(BufferedImage image, int x0, int y0, int x1, int y1) {
        long alpha = 0;
        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                alpha += a;
                red += ((argb >> 16) & 0xFF) * a;
                green += ((argb >> 8) & 0xFF) * a;
                blue += (argb & 0xFF) * a;
                count++;
            }
        }
        if (count == 0 || alpha == 0) {
            return 0;
        }
        int a = (int) (alpha / count);
        int r = (int) (red / alpha);
        int g = (int) (green / alpha);
        int b = (int) (blue / alpha);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
