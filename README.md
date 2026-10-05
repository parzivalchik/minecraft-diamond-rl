# MCRL — a Minecraft bot that learns to mine diamonds

A Fabric 1.21.1 mod exposes the game as a reinforcement-learning environment; a Python PPO agent
learns from 64×64 screen pixels to dig toward diamond ore without dying.
Design: `docs/design.md`.

## One-time setup

```bash
brew install uv gradle
cd python && uv python install 3.12 && uv sync
```

Then create the training world once:

1. `cd mod && ./gradlew runClient`
2. Singleplayer → Create New World → name it **rl_arena**, Game Mode **Survival**, Allow Commands **On**.
3. More → World Type **Superflat** → Customize → Presets → **The Void** → Use Preset → Create New World.

## Training

```bash
cd mod && ./gradlew runClient          # terminal 1; open the rl_arena world
```
```bash
cd python && uv run python train.py --run diamond1      # terminal 2
```
```bash
cd python && uv run tensorboard --logdir runs           # optional: http://localhost:6006
```

- Stop with Ctrl-C; continue later with `uv run python train.py --run diamond1 --resume`.
- Watch the agent: `uv run python watch.py --run diamond1`.
- Check the setup any time: `uv run python smoke.py` (game running, world open).

While training: keep the game window open (it can sit behind other windows, but don't minimize it)
and don't open the pause menu — training simply stalls until you close it.

## Tests

```bash
cd python && uv run pytest
```
```bash
cd mod && ./gradlew test
```
