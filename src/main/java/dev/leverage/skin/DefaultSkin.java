package dev.leverage.skin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.Color;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// Neutral placeholder generated locally, without bundled character textures.
public final class DefaultSkin {
    private static final byte[] PNG = load();
    private static final String SHA1 = hash();

    private DefaultSkin() { }

    public static String sha1() {
        return SHA1;
    }
    public static byte[] png() {
        return PNG.clone();
    }

    public static BufferedImage image() {
        try {
            return ImageIO.read(new ByteArrayInputStream(PNG));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read the placeholder texture.",
                    exception);
        }
    }

    private static byte[] load() {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0x929799));
            graphics.fillRect(0, 0, 32, 16);
            graphics.setColor(new Color(0x666E72));
            graphics.fillRect(16, 16, 24, 16);
            graphics.setColor(new Color(0x80888C));
            graphics.fillRect(40, 16, 16, 16);
            graphics.fillRect(32, 48, 16, 16);
            graphics.setColor(new Color(0x545B60));
            graphics.fillRect(0, 16, 16, 16);
            graphics.fillRect(16, 48, 16, 16);
        } finally { graphics.dispose(); }
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "PNG", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String hash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-1").digest(PNG));
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
