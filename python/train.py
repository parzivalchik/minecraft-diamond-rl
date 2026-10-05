"""Train the diamond-mining agent. Requires the game running with the rl_arena world open."""
from __future__ import annotations

import argparse
from pathlib import Path

from stable_baselines3.common.callbacks import CallbackList, CheckpointCallback
from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.vec_env import DummyVecEnv

from mcrl.curriculum import Curriculum
from mcrl.env import MinecraftEnv
from mcrl.protocol import BridgeClient
from mcrl.training import (MetricsCallback, checkpoints_newest_first, load_newest_checkpoint,
                           make_model, pick_device, save_atomic)


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True, help="run name; files go to runs/<name>/")
    parser.add_argument("--resume", action="store_true", help="continue from the latest checkpoint")
    parser.add_argument("--total-steps", type=int, default=5_000_000,
                        help="environment steps to train in this invocation; with --resume they are "
                             "added on top of the checkpoint's step count (default: %(default)s)")
    parser.add_argument("--port", type=int, default=5005)
    args = parser.parse_args(argv)

    run_dir = Path("runs") / args.run
    checkpoint_dir = run_dir / "checkpoints"
    curriculum_path = run_dir / "curriculum.json"
    if not args.resume and checkpoints_newest_first(checkpoint_dir):
        raise SystemExit(f"run '{args.run}' already has checkpoints in {checkpoint_dir}; "
                         f"pass --resume to continue it, or choose a new --run name")
    curriculum = Curriculum.load(curriculum_path) if args.resume else Curriculum()

    env = MinecraftEnv(client=BridgeClient(port=args.port), curriculum=curriculum)
    vec_env = DummyVecEnv([lambda: Monitor(env)])
    device = pick_device()

    model, checkpoint = (load_newest_checkpoint(checkpoint_dir, vec_env, device) if args.resume
                         else (None, None))
    if checkpoint:
        print(f"Resuming from {checkpoint} at stage {curriculum.stage}")
    else:
        if args.resume:
            print(f"No loadable checkpoint in {checkpoint_dir}; starting fresh")
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
        save_atomic(model, checkpoint_dir / f"ppo_{model.num_timesteps}_steps.zip")
        curriculum.save(curriculum_path)
        env.close()


if __name__ == "__main__":
    main()
