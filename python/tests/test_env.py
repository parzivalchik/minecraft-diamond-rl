import numpy as np
import pytest

from mcrl.curriculum import Curriculum
from mcrl.env import ACTION_NAMES, MinecraftEnv
from mcrl.protocol import BridgeClient, BridgeError
from tests.fake_bridge import make_header


def make_env(bridge, **kwargs):
    return MinecraftEnv(client=BridgeClient(port=bridge.port, max_backoff=0.01), **kwargs)


def test_spaces():
    env = MinecraftEnv(client=BridgeClient(port=1))
    assert env.action_space.n == 12 == len(ACTION_NAMES)
    assert env.observation_space["image"].shape == (64, 64, 12)
    assert env.observation_space["image"].dtype == np.uint8
    assert env.observation_space["state"].shape == (3,)


def test_reset_sends_stage_and_integer_seed(bridge):
    env = make_env(bridge, curriculum=Curriculum(stage=2))
    obs, info = env.reset(seed=123)
    req = bridge.requests[0]
    assert req["cmd"] == "reset" and req["stage"] == 2 and isinstance(req["seed"], int)
    assert info == {"stage": 2}
    assert obs["image"].shape == (64, 64, 12)
    assert np.all(obs["image"] == 1)  # reset frame repeated 4 times
    np.testing.assert_allclose(obs["state"], [1.0, 1.0, 0.0])


def test_same_seed_gives_same_episode_seeds(bridge):
    env = make_env(bridge)
    env.reset(seed=7)
    env.reset(seed=7)
    assert bridge.requests[0]["seed"] == bridge.requests[1]["seed"]


def test_step_stacks_newest_frame_last(bridge):
    env = make_env(bridge)
    env.reset(seed=0)
    obs, reward, terminated, truncated, info = env.step(3)
    assert bridge.requests[1] == {"cmd": "step", "action": 3}
    assert np.all(obs["image"][:, :, :9] == 1)
    assert np.all(obs["image"][:, :, 9:] == 2)
    assert not terminated and not truncated and info == {}


def test_state_vector_reflects_header(bridge):
    bridge.respond = lambda req, n: make_header(health=10.0, food=5, on_fire=True)
    env = make_env(bridge)
    obs, _ = env.reset(seed=0)
    np.testing.assert_allclose(obs["state"], [0.5, 0.25, 1.0])


def test_death_terminates_with_cause(bridge):
    def respond(req, n):
        if n == 3:
            return make_header(dead=True, health=0.0,
                               events=[{"type": "damage", "source": "lava", "amount": 20.0}])
        return make_header()
    bridge.respond = respond
    curriculum = Curriculum()
    env = make_env(bridge, curriculum=curriculum)
    env.reset(seed=0)
    env.step(0)
    _, reward, terminated, truncated, info = env.step(1)
    assert terminated and not truncated
    assert info["death"] is True and info["death_cause"] == "lava" and info["success"] is False
    assert list(curriculum.history) == [False]


def test_all_diamonds_taken_is_success(bridge):
    def respond(req, n):
        if n == 2:
            return make_header(diamonds_remaining=0, nearest_diamond_dist=-1.0,
                               events=[{"type": "block_broken", "block": "minecraft:diamond_ore"}])
        return make_header(diamonds_remaining=1)
    bridge.respond = respond
    env = make_env(bridge)
    env.reset(seed=0)
    _, reward, terminated, _, info = env.step(10)
    assert terminated
    assert info["success"] is True and info["episode_diamonds"] == 1 and info["death"] is False
    assert reward > 9


def test_diamond_and_death_same_step_is_failure(bridge):
    def respond(req, n):
        if n == 2:
            return make_header(dead=True, diamonds_remaining=0,
                               events=[{"type": "block_broken", "block": "minecraft:diamond_ore"},
                                       {"type": "damage", "source": "lava", "amount": 20.0}])
        return make_header(diamonds_remaining=1)
    bridge.respond = respond
    env = make_env(bridge)
    env.reset(seed=0)
    _, _, terminated, _, info = env.step(10)
    assert terminated and info["success"] is False and info["episode_diamonds"] == 1


def test_truncates_at_max_steps(bridge):
    env = make_env(bridge, max_steps=3)
    env.reset(seed=0)
    results = [env.step(0) for _ in range(3)]
    assert [r[3] for r in results] == [False, False, True]
    assert results[-1][4]["success"] is False


def test_disconnect_truncates_then_reset_reconnects(bridge):
    bridge.drop_after = 2
    curriculum = Curriculum()
    env = make_env(bridge, curriculum=curriculum)
    env.reset(seed=0)
    env.step(0)
    _, reward, terminated, truncated, info = env.step(0)
    assert truncated and not terminated and reward == 0.0
    assert info == {"disconnected": True}
    assert list(curriculum.history) == []  # discarded, not counted
    obs, _ = env.reset(seed=1)
    assert obs["image"].shape == (64, 64, 12)


def test_reset_error_reply_raises_bridge_error(bridge):
    bridge.respond = lambda req, n: {"error": "no singleplayer world loaded - open the rl_arena world"}
    env = make_env(bridge)
    with pytest.raises(BridgeError, match="rl_arena"):
        env.reset(seed=0)


@pytest.fixture
def sleeps(monkeypatch):
    """Records retry back-off sleeps instead of sleeping."""
    recorded: list[float] = []
    monkeypatch.setattr("mcrl.env._sleep", recorded.append)
    return recorded


def test_step_error_reply_truncates_without_recording(bridge):
    def respond(req, n):
        if n == 2:
            return {"error": "world unloaded or agent disconnected mid-step"}
        return make_header()
    bridge.respond = respond
    curriculum = Curriculum()
    env = make_env(bridge, curriculum=curriculum)
    obs0, _ = env.reset(seed=0)
    obs, reward, terminated, truncated, info = env.step(0)
    assert truncated and not terminated and reward == 0.0
    assert info == {"bridge_error": "world unloaded or agent disconnected mid-step"}
    assert obs is obs0  # last good observation
    assert list(curriculum.history) == []  # discarded, not counted


def test_drop_during_later_reset_is_retried(bridge, sleeps, capsys):
    env = make_env(bridge)
    env.reset(seed=0)
    bridge.drop_after = 1  # the next request gets no reply: the game "crashes" mid-reset
    obs, _ = env.reset(seed=1)
    assert obs["image"].shape == (64, 64, 12)
    assert [r["cmd"] for r in bridge.requests] == ["reset", "reset", "reset"]
    assert sleeps == [0.5]
    assert "connection lost" in capsys.readouterr().out


def test_bridge_error_on_later_reset_is_retried(bridge, sleeps, capsys):
    def respond(req, n):
        if n == 2:
            return {"error": "no singleplayer world loaded - open the rl_arena world"}
        return make_header()
    bridge.respond = respond
    env = make_env(bridge)
    env.reset(seed=0)
    obs, info = env.reset(seed=1)
    assert obs["image"].shape == (64, 64, 12) and info == {"stage": 1}
    assert len(bridge.requests) == 3
    assert sleeps == [0.5]
    assert "rl_arena" in capsys.readouterr().out


def test_reset_retry_backoff_doubles_up_to_10s(bridge, sleeps):
    def respond(req, n):
        if 2 <= n <= 8:
            return {"error": "integrated server did not respond within 10 s"}
        return make_header()
    bridge.respond = respond
    env = make_env(bridge)
    env.reset(seed=0)
    env.reset(seed=1)
    assert sleeps == [0.5, 1.0, 2.0, 4.0, 8.0, 10.0, 10.0]


def test_first_reset_error_reply_does_not_retry(bridge, sleeps):
    bridge.respond = lambda req, n: {"error": "no singleplayer world loaded - open the rl_arena world"}
    env = make_env(bridge)
    with pytest.raises(BridgeError, match="rl_arena"):
        env.reset(seed=0)
    assert sleeps == [] and len(bridge.requests) == 1
