"""Turns raw bridge events into a scalar reward (spec §8). Pure: no I/O, no game access."""
from __future__ import annotations

from dataclasses import dataclass

DIAMOND_BLOCKS = frozenset({"minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"})
IRON_BLOCKS = frozenset({"minecraft:iron_ore", "minecraft:deepslate_iron_ore"})
COAL_BLOCKS = frozenset({"minecraft:coal_ore", "minecraft:deepslate_coal_ore"})
STONE_BLOCKS = frozenset({"minecraft:stone", "minecraft:deepslate"})
FIRE_SOURCES = frozenset({"lava", "in_fire", "on_fire"})


@dataclass(frozen=True)
class RewardConfig:
    diamond: float = 10.0
    iron: float = 1.0
    coal: float = 0.5
    stone: float = 0.05
    stone_cap: int = 20
    closer_per_block: float = 0.2
    damage_per_heart: float = -0.5
    fire: float = -2.0
    death: float = -10.0
    step: float = -0.001


class RewardTracker:
    """Holds the per-episode state the reward needs (stone count, best diamond distance)."""

    def __init__(self, config: RewardConfig | None = None):
        self.config = config or RewardConfig()
        self._stone_paid = 0
        self._best_dist = -1.0
        self._diamonds_remaining = 0

    def reset(self, header: dict) -> None:
        self._stone_paid = 0
        self._best_dist = float(header["nearest_diamond_dist"])
        self._diamonds_remaining = int(header["diamonds_remaining"])

    def compute(self, header: dict) -> tuple[float, dict]:
        c = self.config
        reward = c.step
        diamonds = 0
        fire = False
        for event in header["events"]:
            if event["type"] == "block_broken":
                block = event["block"]
                if block in DIAMOND_BLOCKS:
                    reward += c.diamond
                    diamonds += 1
                elif block in IRON_BLOCKS:
                    reward += c.iron
                elif block in COAL_BLOCKS:
                    reward += c.coal
                elif block in STONE_BLOCKS and self._stone_paid < c.stone_cap:
                    reward += c.stone
                    self._stone_paid += 1
            elif event["type"] == "damage":
                reward += c.damage_per_heart * (float(event["amount"]) / 2.0)
                if event["source"] in FIRE_SOURCES:
                    fire = True
        if fire:
            reward += c.fire

        dist = float(header["nearest_diamond_dist"])
        remaining = int(header["diamonds_remaining"])
        if remaining < self._diamonds_remaining:
            # A diamond was taken; shape toward the next one starting from here.
            self._best_dist = dist
        elif dist >= 0 and self._best_dist >= 0 and dist < self._best_dist:
            reward += c.closer_per_block * (self._best_dist - dist)
            self._best_dist = dist
        self._diamonds_remaining = remaining

        if header["dead"]:
            reward += c.death
        return reward, {"diamonds": diamonds}
