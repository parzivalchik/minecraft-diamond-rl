"""Gymnasium wrapper around the MCRL bridge (spec §5–§7)."""
from __future__ import annotations

import time
from collections import deque

import gymnasium as gym
import numpy as np
from gymnasium import spaces

from mcrl.curriculum import Curriculum
from mcrl.protocol import FRAME_SIZE, BridgeClient, BridgeError, Reply
from mcrl.rewards import RewardConfig, RewardTracker

RETRY_DELAY = 0.5
RETRY_MAX_DELAY = 10.0
_sleep = time.sleep  # indirection so tests can skip the back-off waits

ACTION_NAMES = [
    "noop", "forward", "back", "strafe_left", "strafe_right", "jump_forward",
    "turn_left", "turn_right", "look_up", "look_down", "attack", "forward_attack",
]


class MinecraftEnv(gym.Env):
    metadata = {"render_modes": []}

    def __init__(self, client: BridgeClient | None = None, curriculum: Curriculum | None = None,
                 reward_config: RewardConfig | None = None, max_steps: int = 1000,
                 frame_stack: int = 4):
        super().__init__()
        self.client = client or BridgeClient()
        self.curriculum = curriculum or Curriculum()
        self.max_steps = max_steps
        self._rewards = RewardTracker(reward_config)
        self._frames: deque[np.ndarray] = deque(maxlen=frame_stack)
        self.action_space = spaces.Discrete(len(ACTION_NAMES))
        self.observation_space = spaces.Dict({
            "image": spaces.Box(0, 255, (FRAME_SIZE, FRAME_SIZE, 3 * frame_stack), np.uint8),
            "state": spaces.Box(0.0, 1.0, (3,), np.float32),
        })
        self._last_obs = None
        self._steps = 0
        self._diamonds = 0
        self._last_damage: str | None = None
        self._stage = self.curriculum.stage
        self._has_reset = False  # until the first successful reset, mod errors fail fast

    def reset(self, *, seed=None, options=None):
        super().reset(seed=seed)
        self._stage = self.curriculum.stage
        episode_seed = int(self.np_random.integers(0, 2**31 - 1))
        reply = self._request_with_retry({"cmd": "reset", "seed": episode_seed, "stage": self._stage})
        self._frames.clear()
        for _ in range(self._frames.maxlen):
            self._frames.append(reply.frame)
        self._rewards.reset(reply.header)
        self._steps = 0
        self._diamonds = 0
        self._last_damage = None
        self._last_obs = self._obs(reply.header)
        self._has_reset = True
        return self._last_obs, {"stage": self._stage}

    def step(self, action):
        try:
            reply = self.client.request({"cmd": "step", "action": int(action)})
        except ConnectionError:
            # Game crashed or restarted: discard this episode (not recorded to the curriculum).
            # SB3 then calls reset(), which reconnects and retries until the rl_arena world is back.
            return self._last_obs, 0.0, False, True, {"disconnected": True}
        except BridgeError as e:
            # The mod failed this step (world unloaded, frame timeout, ...): discard the episode the
            # same way; reset() retries until the mod answers normally again.
            return self._last_obs, 0.0, False, True, {"bridge_error": str(e)}

        header = reply.header
        reward, reward_info = self._rewards.compute(header)
        self._frames.append(reply.frame)
        self._steps += 1
        self._diamonds += reward_info["diamonds"]
        for event in header["events"]:
            if event["type"] == "damage":
                self._last_damage = event["source"]

        dead = bool(header["dead"])
        terminated = dead or header["diamonds_remaining"] == 0
        truncated = not terminated and self._steps >= self.max_steps
        info = {}
        if terminated or truncated:
            success = self._diamonds >= 1 and not dead
            info = {
                "success": success,
                "episode_diamonds": self._diamonds,
                "death": dead,
                "death_cause": self._last_damage if dead else None,
                "stage": self._stage,
                "advanced": self.curriculum.record(success),
            }
        self._last_obs = self._obs(header)
        return self._last_obs, float(reward), terminated, truncated, info

    def close(self):
        self.client.close()

    def _request_with_retry(self, msg: dict) -> Reply:
        """Retry until the mod answers: through game restarts (connection lost) and error replies
        (e.g. world not open yet), backing off 0.5 s doubling to 10 s.

        Exception: before this env's first successful reset an error reply raises immediately, so a
        missing rl_arena world is reported at startup instead of being waited on silently.
        """
        delay = RETRY_DELAY
        while True:
            try:
                return self.client.request(msg)
            except BridgeError as e:
                if not self._has_reset:
                    raise
                reason = f"mod error: {e}"
            except ConnectionError as e:
                reason = str(e)  # request() reconnects (with its own back-off) on the next try
            print(f"{msg['cmd']} failed ({reason}); retrying in {delay:g} s", flush=True)
            _sleep(delay)
            delay = min(delay * 2, RETRY_MAX_DELAY)

    def _obs(self, header: dict) -> dict:
        state = np.array([header["health"] / 20.0, header["food"] / 20.0, float(header["on_fire"])],
                         dtype=np.float32)
        return {
            "image": np.concatenate(list(self._frames), axis=2),
            "state": np.clip(state, 0.0, 1.0),
        }
