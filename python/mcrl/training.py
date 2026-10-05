"""PPO construction, checkpoint discovery, and training metrics (spec §9)."""
from __future__ import annotations

import os
import re
import warnings
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


def checkpoints_newest_first(directory: Path) -> list[Path]:
    """All ppo_<steps>_steps.zip checkpoints in directory, highest step count first."""
    directory = Path(directory)
    if not directory.is_dir():
        return []
    found = [(int(m.group(1)), p) for p in directory.iterdir()
             if (m := _CHECKPOINT_RE.fullmatch(p.name))]
    return [p for _, p in sorted(found, reverse=True)]


def latest_checkpoint(directory: Path) -> Path | None:
    found = checkpoints_newest_first(directory)
    return found[0] if found else None


def load_newest_checkpoint(directory: Path, env, device: str) -> tuple[PPO | None, Path | None]:
    """Load the newest checkpoint that loads cleanly, warning about and skipping broken ones
    (e.g. a file truncated by a crash mid-save). Returns (None, None) if none loads."""
    for path in checkpoints_newest_first(directory):
        try:
            return PPO.load(path, env=env, device=device), path
        except Exception as e:  # noqa: BLE001 - any unreadable checkpoint means "try the older one"
            warnings.warn(f"cannot load checkpoint {path} ({type(e).__name__}: {e}); "
                          f"falling back to an older one", UserWarning)
    return None, None


def save_atomic(model: PPO, path: Path) -> None:
    """Save to a temp name next to path, then os.replace, so path is never a half-written zip."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")  # does not match the checkpoint pattern
    try:
        model.save(tmp)
        os.replace(tmp, path)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise


class MetricsCallback(BaseCallback):
    """Logs diamond/death/curriculum metrics to TensorBoard and persists the curriculum."""

    def __init__(self, curriculum: Curriculum, curriculum_path: Path):
        super().__init__()
        self.curriculum = curriculum
        self.curriculum_path = Path(curriculum_path)
        self.death_counts: dict[str, int] = {}

    def _on_step(self) -> bool:
        deaths_changed = False
        for info in self.locals["infos"]:
            if "success" not in info:
                continue
            self.logger.record_mean("episode/diamonds", info["episode_diamonds"])
            self.logger.record_mean("episode/success_rate", float(info["success"]))
            self.logger.record_mean("episode/death_rate", float(info["death"]))
            if info["death"]:
                cause = info["death_cause"] or "unknown"
                self.death_counts[cause] = self.death_counts.get(cause, 0) + 1
                deaths_changed = True
            if info["advanced"]:
                print(f"Curriculum advanced to stage {self.curriculum.stage}")
                self.curriculum.save(self.curriculum_path)
        if deaths_changed:
            total_deaths = sum(self.death_counts.values())
            for cause, count in self.death_counts.items():
                self.logger.record(f"death_cause/{cause}", count / total_deaths)
        self.logger.record("curriculum/stage", self.curriculum.stage)
        self.logger.record("curriculum/success_rate", self.curriculum.success_rate())
        return True

    def _on_rollout_end(self) -> None:
        self.curriculum.save(self.curriculum_path)
