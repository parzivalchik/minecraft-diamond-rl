"""Watch a trained agent play (no learning)."""
from __future__ import annotations

import argparse
from pathlib import Path

from stable_baselines3 import PPO

from mcrl.curriculum import Curriculum
from mcrl.env import MinecraftEnv
from mcrl.protocol import BridgeClient
from mcrl.training import latest_checkpoint, pick_device


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True)
    parser.add_argument("--checkpoint", type=Path, help="defaults to the latest in runs/<run>/checkpoints")
    parser.add_argument("--episodes", type=int, default=5)
    parser.add_argument("--port", type=int, default=5005)
    args = parser.parse_args(argv)

    run_dir = Path("runs") / args.run
    checkpoint = args.checkpoint or latest_checkpoint(run_dir / "checkpoints")
    if checkpoint is None:
        raise SystemExit(f"no checkpoint found in {run_dir / 'checkpoints'}")
    curriculum = Curriculum.load(run_dir / "curriculum.json")
    curriculum.window = 10**9  # watching must never advance the stage

    env = MinecraftEnv(client=BridgeClient(port=args.port), curriculum=curriculum)
    model = PPO.load(checkpoint, device=pick_device())
    print(f"Watching {checkpoint} at stage {curriculum.stage}")
    try:
        for episode in range(1, args.episodes + 1):
            obs, _ = env.reset()
            total, done, info = 0.0, False, {}
            while not done:
                action, _ = model.predict(obs, deterministic=True)
                obs, reward, terminated, truncated, info = env.step(action)
                total += reward
                done = terminated or truncated
            print(f"episode {episode}: reward={total:.2f} diamonds={info.get('episode_diamonds')} "
                  f"death={info.get('death')} cause={info.get('death_cause')}")
    finally:
        env.close()


if __name__ == "__main__":
    main()
