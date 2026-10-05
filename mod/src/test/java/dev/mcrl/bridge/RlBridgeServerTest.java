package dev.mcrl.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RlBridgeServerTest {
    private RlBridgeServer server;

    @BeforeEach
    void start() throws IOException {
        server = new RlBridgeServer(0);
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.stop();
    }

    private Socket connect() throws IOException {
        Socket s = new Socket(InetAddress.getLoopbackAddress(), server.getPort());
        s.setSoTimeout(5000);
        return s;
    }

    private static void send(Socket s, String json) throws IOException {
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        Protocol.writeMessage(out, json.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static JsonObject readHeader(DataInputStream in) throws IOException {
        return JsonParser.parseString(new String(Protocol.readMessage(in), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private RlBridgeServer.Pending awaitPending() throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            RlBridgeServer.Pending p = server.poll();
            if (p != null) return p;
            Thread.sleep(10);
        }
        fail("no request arrived");
        return null;
    }

    @Test
    void malformedRequestGetsErrorAndConnectionSurvives() throws Exception {
        try (Socket s = connect()) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(s, "{bad");
            assertTrue(readHeader(in).has("error"));
            assertEquals(0, Protocol.readMessage(in).length);

            send(s, "{\"cmd\":\"step\",\"action\":1}");
            RlBridgeServer.Pending p = awaitPending();
            assertEquals(new Request("step", 1, 0, 0), p.request());
            assertTrue(server.isConnected());
        }
    }

    @Test
    void forwardsRequestAndReturnsReply() throws Exception {
        try (Socket s = connect()) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(s, "{\"cmd\":\"reset\",\"seed\":5,\"stage\":1}");
            RlBridgeServer.Pending p = awaitPending();
            JsonObject header = new JsonObject();
            header.addProperty("ok", true);
            p.reply().complete(new RlBridgeServer.Reply(header, new byte[]{9, 9}));
            assertTrue(readHeader(in).get("ok").getAsBoolean());
            assertArrayEquals(new byte[]{9, 9}, Protocol.readMessage(in));
        }
    }

    @Test
    void servesNewClientAfterPreviousOneDiesMidRequest() throws Exception {
        Socket first = connect();
        send(first, "{\"cmd\":\"step\",\"action\":0}");
        RlBridgeServer.Pending orphan = awaitPending();
        first.close();
        orphan.reply().complete(RlBridgeServer.Reply.error("too late"));  // write to dead socket must not wedge

        try (Socket second = connect()) {
            DataInputStream in = new DataInputStream(second.getInputStream());
            send(second, "{\"cmd\":\"step\",\"action\":2}");
            RlBridgeServer.Pending p = awaitPending();
            assertEquals(2, p.request().action());
            p.reply().complete(new RlBridgeServer.Reply(new JsonObject(), new byte[0]));
            assertNotNull(readHeader(in));
        }
    }
}
