package top.syshub.accountsx.common.utils.image;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 头像像素解码：登录界面的多角色选择只用 {@code fill} 画头像，因此解码结果必须是可用的 ARGB 网格。
 */
class AvatarPixelsTest {

    @Test
    void decode_scalesDownAndKeepsAlpha() throws IOException {
        // 4×4 里只有左上 2×2 不透明红，其余透明：降到 2×2 后左上角是红，另外三个角必须是 0（跳过着色）。
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                image.setRGB(x, y, (y < 2 && x < 2) ? 0xFFFF0000 : 0x00000000);
            }
        }

        int[] pixels = AvatarPixels.decode(png(image), 2);

        assertThat(pixels).hasSize(4);
        assertThat(pixels[0]).isEqualTo(0xFFFF0000);
        assertThat(pixels[1]).isZero();
        assertThat(pixels[2]).isZero();
        assertThat(pixels[3]).isZero();
    }

    @Test
    void decode_opaqueImage_keepsEveryPixel() throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, 0xFF336699);
            }
        }

        int[] pixels = AvatarPixels.decode(png(image), 16);

        assertThat(pixels).hasSize(256).containsOnly(0xFF336699);
    }

    @Test
    void decode_missingOrBrokenBytes_returnsNull() {
        assertThat(AvatarPixels.decode(null, 16)).isNull();
        assertThat(AvatarPixels.decode(new byte[0], 16)).isNull();
        assertThat(AvatarPixels.decode("not a png".getBytes(), 16)).isNull();
        assertThat(AvatarPixels.decode(new byte[] {1, 2, 3}, 0)).isNull();
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
