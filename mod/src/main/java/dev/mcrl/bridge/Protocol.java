package dev.mcrl.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcrl.Actions;
import dev.mcrl.arena.StageConfig;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Wire format (spec §4): every message is a 4-byte big-endian length followed by the payload. */
public final class Protocol {
    public static final int MAX_MESSAGE = 16 * 1024 * 1024;

    private Protocol() {}

    public static byte[] readMessage(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_MESSAGE) throw new IOException("bad message length " + length);
        byte[] payload = new byte[length];
        in.readFully(payload);
        return payload;
    }

    public static void writeMessage(DataOutputStream out, byte[] payload) throws IOException {
        out.writeInt(payload.length);
        out.write(payload);
    }

    /** Header message then frame message; a null frame is sent as an empty message. */
    public static void writeReply(DataOutputStream out, JsonObject header, byte[] frame) throws IOException {
        writeMessage(out, header.toString().getBytes(StandardCharsets.UTF_8));
        writeMessage(out, frame == null ? new byte[0] : frame);
        out.flush();
    }

    public static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o;
    }

    public static Request parseRequest(byte[] payload) {
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
            o = e.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed JSON request");
        }
        try {
            return parseFields(o);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            // e.g. {"cmd":null}, {"cmd":[1]}, {"seed":{}}: wrong JSON types must never escape as other exceptions
            throw new IllegalArgumentException("malformed request field");
        }
    }

    private static Request parseFields(JsonObject o) {
        if (!o.has("cmd")) throw new IllegalArgumentException("missing cmd");
        String cmd = primitive(o, "cmd").getAsString();
        return switch (cmd) {
            case "close" -> new Request(cmd, 0, 0L, 0);
            case "step" -> {
                int action = intField(o, "action");
                if (action < 0 || action >= Actions.COUNT) {
                    throw new IllegalArgumentException("action out of range: " + action);
                }
                yield new Request(cmd, action, 0L, 0);
            }
            case "reset" -> {
                long seed = 0L;
                if (o.has("seed")) {
                    try {
                        seed = primitive(o, "seed").getAsLong();
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("seed must be an integer");
                    }
                }
                int stage = intField(o, "stage");
                StageConfig.forStage(stage); // throws IllegalArgumentException for unknown stages
                yield new Request(cmd, 0, seed, stage);
            }
            default -> throw new IllegalArgumentException("unknown cmd: " + cmd);
        };
    }

    /** Gson coerces single-element arrays to scalars; insist on a real JSON primitive instead. */
    private static JsonElement primitive(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive()) throw new IllegalArgumentException(name + " must be a JSON scalar");
        return e;
    }

    private static int intField(JsonObject o, String name) {
        if (!o.has(name)) throw new IllegalArgumentException("missing " + name);
        try {
            return primitive(o, name).getAsInt();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }
}
