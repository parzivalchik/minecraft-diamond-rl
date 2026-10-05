package dev.mcrl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrameCaptureTest {
    private static int abgr(int r, int g, int b) {
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    @Test
    void outputIs64x64Rgb() {
        byte[] out = FrameCapture.downsample(320, 240, (x, y) -> abgr(10, 20, 30), 64);
        assertEquals(64 * 64 * 3, out.length);
        assertEquals(10, out[0] & 0xFF);
        assertEquals(20, out[1] & 0xFF);
        assertEquals(30, out[2] & 0xFF);
    }

    @Test
    void averagesEachBox() {
        // 4x4 source → 2x2 output. Left half red 200, right half red 100.
        byte[] out = FrameCapture.downsample(4, 4, (x, y) -> abgr(x < 2 ? 200 : 100, 0, 0), 2);
        assertEquals(200, out[0] & 0xFF);
        assertEquals(100, out[3] & 0xFF);
    }

    @Test
    void rowMajorTopLeftOrigin() {
        // Top row bright, rest dark.
        byte[] out = FrameCapture.downsample(64, 64, (x, y) -> y == 0 ? abgr(255, 255, 255) : abgr(0, 0, 0), 64);
        assertEquals(255, out[0] & 0xFF);
        assertEquals(0, out[64 * 3] & 0xFF); // first pixel of second row
    }

    @Test
    void handlesSourceSmallerThanOutput() {
        byte[] out = FrameCapture.downsample(32, 32, (x, y) -> abgr(7, 7, 7), 64);
        assertEquals(7, out[out.length - 1] & 0xFF);
    }
}
