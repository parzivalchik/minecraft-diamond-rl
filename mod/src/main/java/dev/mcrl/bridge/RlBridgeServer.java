package dev.mcrl.bridge;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Localhost TCP server for one agent at a time. Runs on its own thread and never touches game
 * state: each request is queued for the game thread, which completes the reply future.
 */
public final class RlBridgeServer {
    private static final Logger LOG = LoggerFactory.getLogger("mcrl");

    /**
     * A request awaiting the game thread. If the agent disconnects while waiting, the bridge cancels
     * {@code reply}; the game thread must skip pendings whose reply is already done
     * ({@link #isAbandoned()}) instead of executing them.
     */
    public record Pending(Request request, CompletableFuture<Reply> reply) {
        public boolean isAbandoned() { return reply.isDone(); }
    }

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
            } catch (RuntimeException e) {
                // backstop: a bug while serving one connection must not kill the accept thread
                LOG.error("bridge connection failed unexpectedly", e);
            } finally {
                connected = false;
            }
        }
    }

    private void serve(Socket socket) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(socket.getInputStream());
        DataInputStream in = new DataInputStream(buffered);
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
            Reply reply = awaitReply(socket, buffered, future);
            if (reply == null) return; // peer died mid-request; future already cancelled
            Protocol.writeReply(out, reply.header(), reply.frame());
        }
    }

    /**
     * Waits for the game thread's reply in short slices, checking between slices that the agent is
     * still connected (it never sends while awaiting a reply, so a readable EOF means it died). On
     * disconnect the pending future is cancelled and null is returned.
     */
    private Reply awaitReply(Socket socket, BufferedInputStream in, CompletableFuture<Reply> future)
            throws IOException {
        while (true) {
            try {
                return future.get(50, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // still waiting: fall through to the liveness check
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(false);
                return null;
            } catch (ExecutionException e) {
                return Reply.error(String.valueOf(e.getCause()));
            } catch (CancellationException e) {
                return Reply.error("request cancelled");
            }
            if (peerClosed(socket, in)) {
                future.cancel(false);
                LOG.info("agent disconnected mid-request; abandoning it");
                return null;
            }
        }
    }

    private static boolean peerClosed(Socket socket, BufferedInputStream in) {
        try {
            socket.setSoTimeout(5);
            try {
                in.mark(1);
                if (in.read() == -1) return true;
                in.reset(); // unexpected byte: keep it for the next readMessage
                return false;
            } finally {
                socket.setSoTimeout(0);
            }
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return true; // reset or closed
        }
    }
}
