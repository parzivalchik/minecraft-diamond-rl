# MCRL — a Minecraft bot that learns to mine diamonds

A Fabric 1.21.1 mod exposes the game as a reinforcement-learning environment; a Python PPO agent
learns from 64×64 screen pixels to dig toward diamond ore without dying.
Design: `docs/design.md`.

## One-time setup

The mod needs **JDK 21** (`java -version` should say 21; e.g. `brew install --cask temurin@21`).
`./gradlew` downloads Gradle itself and builds and runs the game with that JDK, so no separate
Gradle install is needed.

```bash
brew install uv
cd python && uv python install 3.12 && uv sync
```

Then create the training world once. The quickest way is
`cd mod && ./gradlew runClient -Pmcrl.autoWorld=true`: at the title screen the mod opens the
**rl_arena** world, creating it first if needed (Survival, Allow Commands on, default Superflat —
the arena sits in its own bedrock box at y=100, so Void isn't required). Use the same flag on later
launches to skip the menus. Or create it by hand:

1. `cd mod && ./gradlew runClient`
2. Singleplayer → Create New World → name it **rl_arena**, Game Mode **Survival**, Allow Commands **On**.
3. More → World Type **Superflat** → Customize → Presets → **The Void** → Use Preset → Create New World.

## Training

```bash
cd mod && ./gradlew runClient          # terminal 1; open the rl_arena world
```
```bash
cd python && caffeinate -dis uv run python train.py --run diamond1      # terminal 2
```
```bash
cd python && uv run tensorboard --logdir runs           # optional: http://localhost:6006
```

- `caffeinate -dis` keeps a laptop (and its display) awake for multi-hour runs; macOS sleep
  would otherwise pause the game and training. Keep the laptop on power.
- Stop with Ctrl-C; continue later with `uv run python train.py --run diamond1 --resume`
  (`--total-steps` is then added on top of the steps already trained). Reusing a run name without
  `--resume` is refused so old checkpoints are never mixed with a new run.
- Watch the agent: `uv run python watch.py --run diamond1`.
- Check the setup any time: `uv run python smoke.py` (game running, world open).

The agent has 13 actions: movement, turning, attacking, and **tunnel forward** (action 12), a
macro that digs the 1×2 blocks ahead and walks into the gap, charged the real break time in game
ticks. The curriculum has stages 0–4 (7×7 → 9×9 → 11×11 → 15×15 → 25×25 arenas); the agent advances
at ≥ 60% success over 100 episodes. Runs started before 2026-10-07 (12 actions, old stage numbers)
cannot be resumed — start a new run name.

The game ticks at 100 Hz while an agent is connected; change it with
`./gradlew runClient -Pmcrl.tickRate=200` (likewise `-Pmcrl.port=5006`, then pass `--port` to the scripts).

While training: keep the game window open (it can sit behind other windows, but don't minimize it)
and don't open the pause menu — training simply stalls until you close it.

## Tests

```bash
cd python && uv run pytest
```
```bash
cd mod && ./gradlew test
```
