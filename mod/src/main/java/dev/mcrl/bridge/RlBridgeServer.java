package dev.mcrl.bridge;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Localhost TCP server for one agent at a time. Runs on its own thread and never touches game
 * state: each request is queued for the game thread, which completes the reply future.
 */
public final class RlBridgeServer {
    private static final Logger LOG = LoggerFactory.getLogger("mcrl");

    public record Pending(Request request, CompletableFuture<Reply> reply) {}

    public record Reply(JsonObject header, byte[] frame) {
        public static Reply error(String message) {
            return new Reply(Protocol.error(message), new byte[0]);
        }
    }

    private final int requestedPort;
    private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private volatile boolean connected;
    private ServerSocket serverSocket;

    public RlBridgeServer(int port) {
        this.requestedPort = port;
    }

    /** Binds synchronously (so a busy port fails loudly at startup), then accepts on a daemon thread. */
    public void start() throws IOException {
        serverSocket = new ServerSocket(requestedPort, 1, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::acceptLoop, "mcrl-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    public int getPort() { return serverSocket.getLocalPort(); }

    public boolean isConnected() { return connected; }

    public Pending poll() { return queue.poll(); }

    public void stop() throws IOException { serverSocket.close(); }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                connected = true;
                LOG.info("agent connected from {}", socket.getRemoteSocketAddress());
                serve(socket);
            } catch (IOException e) {
                if (!serverSocket.isClosed()) LOG.info("agent disconnected: {}", e.toString());
            } finally {
                connected = false;
            }
        }
    }

    private void serve(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        while (true) {
            byte[] payload = Protocol.readMessage(in); // EOFException when the agent disconnects
            Request request;
            try {
                request = Protocol.parseRequest(payload);
            } catch (IllegalArgumentException e) {
                Protocol.writeReply(out, Protocol.error(e.getMessage()), null);
                continue;
            }
            if (request.cmd().equals("close")) return;
            CompletableFuture<Reply> future = new CompletableFuture<>();
            queue.add(new Pending(request, future));
            Reply reply;
            try {
                reply = future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                reply = Reply.error(String.valueOf(e.getCause()));
            }
            Protocol.writeReply(out, reply.header(), reply.frame());
        }
    }
}
