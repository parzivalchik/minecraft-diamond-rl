package dev.mcrl.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
    @Timeout(30)
    void wrongTypedFieldsGetErrorRepliesAndServerKeepsServing() throws Exception {
        String[] bad = {
                "{\"cmd\":null}", "{\"cmd\":{}}", "{\"cmd\":[1,2]}",
                "{\"cmd\":\"reset\",\"seed\":{},\"stage\":1}",
                "{\"cmd\":\"reset\",\"seed\":null,\"stage\":1}",
        };
        try (Socket s = connect()) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            for (String json : bad) {
                send(s, json);
                assertTrue(readHeader(in).has("error"), json);
                assertEquals(0, Protocol.readMessage(in).length);
            }
            send(s, "{\"cmd\":\"step\",\"action\":1}");
            assertEquals(1, awaitPending().request().action());
        }
        // the accept thread must also still be alive for brand-new connections
        try (Socket s2 = connect()) {
            send(s2, "{\"cmd\":\"step\",\"action\":3}");
            assertEquals(3, awaitPending().request().action());
        }
    }

    @Test
    @Timeout(30)
    void abandonsOrphanedRequestAndServesNewClient() throws Exception {
        Socket first = connect();
        send(first, "{\"cmd\":\"step\",\"action\":0}");
        RlBridgeServer.Pending orphan = awaitPending();
        first.close();  // the orphan is deliberately NOT completed: the bridge must abandon it itself

        try (Socket second = connect()) {
            DataInputStream in = new DataInputStream(second.getInputStream());
            send(second, "{\"cmd\":\"step\",\"action\":2}");
            RlBridgeServer.Pending p = awaitPending();
            assertEquals(2, p.request().action());
            assertTrue(orphan.isAbandoned());
            assertTrue(orphan.reply().isCancelled());
            p.reply().complete(new RlBridgeServer.Reply(new JsonObject(), new byte[0]));
            assertNotNull(readHeader(in));
        }
    }

    @Test
    @Timeout(30)
    void lateReplyToDeadClientDoesNotWedgeServer() throws Exception {
        Socket first = connect();
        send(first, "{\"cmd\":\"step\",\"action\":0}");
        RlBridgeServer.Pending orphan = awaitPending();
        first.close();
        orphan.reply().complete(RlBridgeServer.Reply.error("too late"));  // may race with abandonment; both are fine

        try (Socket second = connect()) {
            DataInputStream in = new DataInputStream(second.getInputStream());
            send(second, "{\"cmd\":\"step\",\"action\":2}");
            RlBridgeServer.Pending p = awaitPending();
            assertEquals(2, p.request().action());
            p.reply().complete(new RlBridgeServer.Reply(new JsonObject(), new byte[0]));
            assertNotNull(readHeader(in));
        }
    }

    @Test
    @Timeout(30)
    void slowReplyToLiveClientIsStillDelivered() throws Exception {
        try (Socket s = connect()) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(s, "{\"cmd\":\"step\",\"action\":4}");
            RlBridgeServer.Pending p = awaitPending();
            Thread.sleep(300); // several liveness-check slices pass with the client still connected
            assertFalse(p.isAbandoned());
            JsonObject h = new JsonObject();
            h.addProperty("ok", true);
            p.reply().complete(new RlBridgeServer.Reply(h, new byte[]{7}));
            assertTrue(readHeader(in).get("ok").getAsBoolean());
            assertArrayEquals(new byte[]{7}, Protocol.readMessage(in));
        }
    }
}
