package dev.mcrl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Buffers per-step facts from server events until the client drains them into a reply. */
public final class EpisodeTracker {
    private final List<JsonObject> events = new ArrayList<>();
    private boolean dead;

    public synchronized void onBlockBroken(String blockId) {
        JsonObject e = new JsonObject();
        e.addProperty("type", "block_broken");
        e.addProperty("block", blockId);
        events.add(e);
    }

    public synchronized void onDamage(String source, float amount) {
        JsonObject e = new JsonObject();
        e.addProperty("type", "damage");
        e.addProperty("source", source);
        e.addProperty("amount", amount);
        events.add(e);
    }

    public synchronized void onDeath() { dead = true; }

    public synchronized boolean isDead() { return dead; }

    public synchronized JsonArray drainEvents() {
        JsonArray out = new JsonArray();
        events.forEach(out::add);
        events.clear();
        return out;
    }

    public synchronized void reset() {
        events.clear();
        dead = false;
    }
}
