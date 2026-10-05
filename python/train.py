"""Train the diamond-mining agent. Requires the game running with the rl_arena world open."""
from __future__ import annotations

import argparse
from pathlib import Path

from stable_baselines3 import PPO
from stable_baselines3.common.callbacks import CallbackList, CheckpointCallback
from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.vec_env import DummyVecEnv

from mcrl.curriculum import Curriculum
from mcrl.env import MinecraftEnv
from mcrl.protocol import BridgeClient
from mcrl.training import MetricsCallback, latest_checkpoint, make_model, pick_device


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True, help="run name; files go to runs/<name>/")
    parser.add_argument("--resume", action="store_true", help="continue from the latest checkpoint")
    parser.add_argument("--total-steps", type=int, default=5_000_000)
    parser.add_argument("--port", type=int, default=5005)
    args = parser.parse_args(argv)

    run_dir = Path("runs") / args.run
    checkpoint_dir = run_dir / "checkpoints"
    curriculum_path = run_dir / "curriculum.json"
    curriculum = Curriculum.load(curriculum_path) if args.resume else Curriculum()

    env = MinecraftEnv(client=BridgeClient(port=args.port), curriculum=curriculum)
    vec_env = DummyVecEnv([lambda: Monitor(env)])
    device = pick_device()

    checkpoint = latest_checkpoint(checkpoint_dir) if args.resume else None
    if checkpoint:
        print(f"Resuming from {checkpoint} at stage {curriculum.stage}")
        model = PPO.load(checkpoint, env=vec_env, device=device)
    else:
        if args.resume:
            print(f"No checkpoint in {checkpoint_dir}; starting fresh")
        model = make_model(vec_env, run_dir / "tb", device)

    callbacks = CallbackList([
        CheckpointCallback(save_freq=20_000, save_path=str(checkpoint_dir), name_prefix="ppo"),
        MetricsCallback(curriculum, curriculum_path),
    ])
    try:
        model.learn(total_timesteps=args.total_steps, callback=callbacks,
                    reset_num_timesteps=checkpoint is None, tb_log_name="ppo")
    except KeyboardInterrupt:
        print("Interrupted; saving checkpoint")
    finally:
        model.save(checkpoint_dir / f"ppo_{model.num_timesteps}_steps")
        curriculum.save(curriculum_path)
        env.close()


if __name__ == "__main__":
    main()
