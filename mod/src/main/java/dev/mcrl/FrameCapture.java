package dev.mcrl;

import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

import java.util.function.IntBinaryOperator;

/** Reads the rendered world and box-downsamples it to a 64x64 RGB frame (spec §3.3). */
public final class FrameCapture {
    public static final int SIZE = 64;

    private FrameCapture() {}

    /** Must be called on the render thread. */
    public static byte[] capture(Framebuffer framebuffer) {
        try (NativeImage image = ScreenshotRecorder.takeScreenshot(framebuffer)) {
            return downsample(image.getWidth(), image.getHeight(), image::getColor, SIZE);
        }
    }

    /** @param abgrAt pixel lookup returning ABGR-packed ints (NativeImage's format in 1.21.1). */
    public static byte[] downsample(int width, int height, IntBinaryOperator abgrAt, int size) {
        byte[] out = new byte[size * size * 3];
        for (int oy = 0; oy < size; oy++) {
            int y0 = oy * height / size;
            int y1 = Math.max(y0 + 1, (oy + 1) * height / size);
            for (int ox = 0; ox < size; ox++) {
                int x0 = ox * width / size;
                int x1 = Math.max(x0 + 1, (ox + 1) * width / size);
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int c = abgrAt.applyAsInt(x, y);
                        r += c & 0xFF;
                        g += (c >>> 8) & 0xFF;
                        b += (c >>> 16) & 0xFF;
                        n++;
                    }
                }
                int i = (oy * size + ox) * 3;
                out[i] = (byte) (r / n);
                out[i + 1] = (byte) (g / n);
                out[i + 2] = (byte) (b / n);
            }
        }
        return out;
    }
}
