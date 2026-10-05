import pytest
from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.vec_env import DummyVecEnv

from mcrl.env import MinecraftEnv
from mcrl.protocol import BridgeClient
from mcrl.training import (PPO_HYPERPARAMS, MetricsCallback, checkpoints_newest_first,
                           latest_checkpoint, load_newest_checkpoint, make_model, save_atomic)


def test_hyperparams_match_spec():
    assert PPO_HYPERPARAMS == {
        "n_steps": 1024, "batch_size": 256, "learning_rate": 2.5e-4, "ent_coef": 0.01,
        "gamma": 0.995, "gae_lambda": 0.95, "clip_range": 0.2, "n_epochs": 4,
    }


def test_latest_checkpoint_picks_highest_step(tmp_path):
    for name in ["ppo_20000_steps.zip", "ppo_100000_steps.zip", "ppo_40000_steps.zip", "other.zip"]:
        (tmp_path / name).touch()
    assert latest_checkpoint(tmp_path) == tmp_path / "ppo_100000_steps.zip"


def test_latest_checkpoint_missing_dir_returns_none(tmp_path):
    assert latest_checkpoint(tmp_path / "does-not-exist") is None
    assert latest_checkpoint(tmp_path) is None


def test_ppo_trains_against_fake_bridge(bridge, tmp_path):
    env = MinecraftEnv(client=BridgeClient(port=bridge.port), max_steps=20)
    vec = DummyVecEnv([lambda: Monitor(env)])
    model = make_model(vec, tensorboard_dir=None, device="cpu", n_steps=32, batch_size=32)
    callback = MetricsCallback(env.curriculum, tmp_path / "curriculum.json")
    model.learn(total_timesteps=64, callback=callback)
    model.save(tmp_path / "ppo_64_steps")
    assert latest_checkpoint(tmp_path) == tmp_path / "ppo_64_steps.zip"
    assert (tmp_path / "curriculum.json").exists()
    assert model.num_timesteps == 64


def test_death_cause_metrics_are_fractions_of_all_deaths(bridge, tmp_path):
    from stable_baselines3.common.logger import configure

    env = MinecraftEnv(client=BridgeClient(port=bridge.port), max_steps=20)
    model = make_model(DummyVecEnv([lambda: Monitor(env)]), tensorboard_dir=None, device="cpu")
    model.set_logger(configure(None, []))
    callback = MetricsCallback(env.curriculum, tmp_path / "curriculum.json")
    callback.init_callback(model)

    def death(cause):
        return {"success": False, "episode_diamonds": 0, "death": True,
                "death_cause": cause, "advanced": False}

    for cause in ["lava", "fall", "lava"]:
        callback.locals = {"infos": [death(cause)]}
        callback._on_step()

    values = model.logger.name_to_value
    assert values["death_cause/lava"] == pytest.approx(2 / 3)
    assert values["death_cause/fall"] == pytest.approx(1 / 3)


def test_checkpoints_newest_first_orders_by_step(tmp_path):
    for name in ["ppo_20000_steps.zip", "ppo_100000_steps.zip", "ppo_40000_steps.zip", "other.zip",
                 "ppo_200000_steps.zip.tmp"]:
        (tmp_path / name).touch()
    assert checkpoints_newest_first(tmp_path) == [
        tmp_path / "ppo_100000_steps.zip", tmp_path / "ppo_40000_steps.zip", tmp_path / "ppo_20000_steps.zip"]
    assert checkpoints_newest_first(tmp_path / "does-not-exist") == []


def _tiny_model(bridge):
    env = MinecraftEnv(client=BridgeClient(port=bridge.port), max_steps=20)
    vec = DummyVecEnv([lambda: Monitor(env)])
    return make_model(vec, tensorboard_dir=None, device="cpu", n_steps=32, batch_size=32), vec


def test_save_atomic_writes_loadable_checkpoint_without_temp_files(bridge, tmp_path):
    model, vec = _tiny_model(bridge)
    target = tmp_path / "checkpoints" / "ppo_0_steps.zip"
    save_atomic(model, target)
    assert [p.name for p in target.parent.iterdir()] == ["ppo_0_steps.zip"]
    loaded, path = load_newest_checkpoint(target.parent, env=vec, device="cpu")
    assert path == target and loaded is not None


def test_load_newest_checkpoint_falls_back_past_corrupt_one(bridge, tmp_path):
    model, vec = _tiny_model(bridge)
    model.save(tmp_path / "ppo_64_steps.zip")
    (tmp_path / "ppo_128_steps.zip").write_bytes(b"truncated by a crash")
    with pytest.warns(UserWarning, match="ppo_128_steps"):
        loaded, path = load_newest_checkpoint(tmp_path, env=vec, device="cpu")
    assert path == tmp_path / "ppo_64_steps.zip" and loaded is not None


def test_load_newest_checkpoint_none_loadable(bridge, tmp_path):
    _, vec = _tiny_model(bridge)
    assert load_newest_checkpoint(tmp_path, env=vec, device="cpu") == (None, None)
    (tmp_path / "ppo_10_steps.zip").write_bytes(b"garbage")
    with pytest.warns(UserWarning):
        assert load_newest_checkpoint(tmp_path, env=vec, device="cpu") == (None, None)
