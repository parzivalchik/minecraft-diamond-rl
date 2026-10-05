"""PPO construction, checkpoint discovery, and training metrics (spec §9)."""
from __future__ import annotations

import re
from pathlib import Path

import torch
from stable_baselines3 import PPO
from stable_baselines3.common.callbacks import BaseCallback

from mcrl.curriculum import Curriculum

PPO_HYPERPARAMS = {
    "n_steps": 1024,
    "batch_size": 256,
    "learning_rate": 2.5e-4,
    "ent_coef": 0.01,
    "gamma": 0.995,
    "gae_lambda": 0.95,
    "clip_range": 0.2,
    "n_epochs": 4,
}

_CHECKPOINT_RE = re.compile(r"ppo_(\d+)_steps\.zip")


def pick_device() -> str:
    return "mps" if torch.backends.mps.is_available() else "cpu"


def make_model(vec_env, tensorboard_dir: Path | None, device: str, **overrides) -> PPO:
    params = {**PPO_HYPERPARAMS, **overrides}
    return PPO("MultiInputPolicy", vec_env, device=device, verbose=1,
               tensorboard_log=str(tensorboard_dir) if tensorboard_dir else None, **params)


def latest_checkpoint(directory: Path) -> Path | None:
    directory = Path(directory)
    if not directory.is_dir():
        return None
    found = [(int(m.group(1)), p) for p in directory.iterdir()
             if (m := _CHECKPOINT_RE.fullmatch(p.name))]
    return max(found)[1] if found else None


class MetricsCallback(BaseCallback):
    """Logs diamond/death/curriculum metrics to TensorBoard and persists the curriculum."""

    def __init__(self, curriculum: Curriculum, curriculum_path: Path):
        super().__init__()
        self.curriculum = curriculum
        self.curriculum_path = Path(curriculum_path)

    def _on_step(self) -> bool:
        for info in self.locals["infos"]:
            if "success" not in info:
                continue
            self.logger.record_mean("episode/diamonds", info["episode_diamonds"])
            self.logger.record_mean("episode/success_rate", float(info["success"]))
            self.logger.record_mean("episode/death_rate", float(info["death"]))
            if info["death"]:
                self.logger.record_mean(f"death_cause/{info['death_cause'] or 'unknown'}", 1.0)
            if info["advanced"]:
                print(f"Curriculum advanced to stage {self.curriculum.stage}")
                self.curriculum.save(self.curriculum_path)
        self.logger.record("curriculum/stage", self.curriculum.stage)
        self.logger.record("curriculum/success_rate", self.curriculum.success_rate())
        return True

    def _on_rollout_end(self) -> None:
        self.curriculum.save(self.curriculum_path)
