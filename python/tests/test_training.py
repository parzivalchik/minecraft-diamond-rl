from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.vec_env import DummyVecEnv

from mcrl.env import MinecraftEnv
from mcrl.protocol import BridgeClient
from mcrl.training import PPO_HYPERPARAMS, MetricsCallback, latest_checkpoint, make_model


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
