package dev.mcrl;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EpisodeTrackerTest {
    @Test
    void recordsAndDrainsEvents() {
        EpisodeTracker t = new EpisodeTracker();
        t.onBlockBroken("minecraft:diamond_ore");
        t.onDamage("lava", 4.0f);
        JsonArray events = t.drainEvents();
        assertEquals(2, events.size());
        assertEquals("block_broken", events.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("minecraft:diamond_ore", events.get(0).getAsJsonObject().get("block").getAsString());
        assertEquals("lava", events.get(1).getAsJsonObject().get("source").getAsString());
        assertEquals(4.0f, events.get(1).getAsJsonObject().get("amount").getAsFloat());
        assertEquals(0, t.drainEvents().size());
    }

    @Test
    void deathFlagPersistsUntilReset() {
        EpisodeTracker t = new EpisodeTracker();
        assertFalse(t.isDead());
        t.onDeath();
        t.drainEvents();
        assertTrue(t.isDead());
        t.onBlockBroken("minecraft:stone");
        t.reset();
        assertFalse(t.isDead());
        assertEquals(0, t.drainEvents().size());
    }
}
