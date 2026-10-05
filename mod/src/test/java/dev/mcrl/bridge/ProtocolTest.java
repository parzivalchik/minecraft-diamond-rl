package dev.mcrl.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    private static Request parse(String json) {
        return Protocol.parseRequest(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesValidRequests() {
        assertEquals(new Request("reset", 0, 1234L, 1), parse("{\"cmd\":\"reset\",\"seed\":1234,\"stage\":1}"));
        assertEquals(new Request("step", 7, 0L, 0), parse("{\"cmd\":\"step\",\"action\":7}"));
        assertEquals(new Request("close", 0, 0L, 0), parse("{\"cmd\":\"close\"}"));
    }

    @Test
    void rejectsBadRequests() {
        assertThrows(IllegalArgumentException.class, () -> parse("{not json"));
        assertThrows(IllegalArgumentException.class, () -> parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"action\":1}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"cmd\":\"fly\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"cmd\":\"step\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"cmd\":\"step\",\"action\":12}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"cmd\":\"step\",\"action\":\"x\"}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"cmd\":\"reset\",\"seed\":1,\"stage\":3}"));
    }

    @Test
    void replyRoundTrip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonObject header = new JsonObject();
        header.addProperty("health", 20.0);
        Protocol.writeReply(new DataOutputStream(bytes), header, new byte[]{1, 2, 3});
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals("{\"health\":20.0}", new String(Protocol.readMessage(in), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{1, 2, 3}, Protocol.readMessage(in));
    }

    @Test
    void nullFrameIsWrittenAsEmpty() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Protocol.writeReply(new DataOutputStream(bytes), Protocol.error("boom"), null);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals("{\"error\":\"boom\"}", new String(Protocol.readMessage(in), StandardCharsets.UTF_8));
        assertEquals(0, Protocol.readMessage(in).length);
    }

    @Test
    void rejectsNegativeLength() {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(new byte[]{(byte) 0xFF, 0, 0, 0}));
        assertThrows(IOException.class, () -> Protocol.readMessage(in));
    }
}
