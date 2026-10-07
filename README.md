# MCRL — a Minecraft bot that learns to mine diamonds

A reinforcement-learning agent that learns, **from 64×64 screen pixels**, to dig toward diamond ore
in Minecraft **without dying**. It has two halves:

- **`mod/`** — a Fabric 1.21.1 client mod that turns the game into a step-by-step RL environment
  and serves it over a local TCP socket.
- **`python/`** — a Gymnasium environment plus a Stable-Baselines3 **PPO** trainer that talks to
  the mod, computes rewards, and runs a curriculum of progressively harder arenas.

It is built to run on a single laptop (developed on an Apple M2 with 8 GB RAM).

---

## Contents

- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Setup](#setup)
- [Quick start](#quick-start)
- [Training](#training)
- [Watching and checking the agent](#watching-and-checking-the-agent)
- [Configuration](#configuration)
- [The environment](#the-environment)
  - [Actions](#actions)
  - [Observations](#observations)
  - [Rewards](#rewards)
  - [Curriculum](#curriculum)
- [Results so far](#results-so-far)
- [Project layout](#project-layout)
- [Wire protocol](#wire-protocol)
- [Tests](#tests)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)

---

## How it works

```
┌──────────────── Minecraft client (Fabric 1.21.1 mod) ────────────────┐
│  RlBridgeServer (TCP 127.0.0.1:5005, background thread)              │
│        │ queue                                                        │
│        ▼                                                              │
│  StepController (client tick) ── applies 1 action for N game ticks   │
│        │                          while the server is tick-stepped    │
│        ├─ FrameCapture ── 64×64 RGB frame of the rendered world       │
│        └─ ArenaManager ── builds random arenas, reports raw events    │
└──────────────────────────────────────────────────────────────────────┘
                 ▲   length-prefixed JSON + raw frame bytes
                 ▼
┌──────────────────────── Python 3.12 (uv) ────────────────────────────┐
│  BridgeClient ◄── MinecraftEnv (Gymnasium) ◄── PPO (Stable-Baselines3)│
│                     ├─ RewardTracker  (events → reward)               │
│                     └─ Curriculum     (success rate → arena stage)    │
└──────────────────────────────────────────────────────────────────────┘
```

Each **step**:

1. Python sends an action index.
2. The mod applies it for a fixed number of game ticks (4 for normal actions, longer for the
   tunnel action), advancing the integrated server by exactly that many ticks. Between steps the
   world is tick-frozen, so slow Python code never desyncs the simulation.
3. The mod captures the rendered world as a 64×64 image and replies with the frame plus raw facts:
   health, food, fire, position, blocks broken, damage taken, death, distance to the nearest diamond.
4. Python turns those facts into a reward, stacks the last 4 frames as the observation, and feeds
   PPO.

The split is deliberate: **the mod reports facts, Python decides rewards and curriculum**, so reward
tuning never needs a mod rebuild.

Deaths are cancelled inside the mod (no death screen); the episode simply ends and the next reset
rebuilds the arena and restores the player.

---

## Requirements

| What | Version / notes |
|---|---|
| macOS (tested) | Apple Silicon M2, 8 GB RAM. Linux should work; untested. |
| JDK | **21** (`java -version` must report 21), e.g. `brew install --cask temurin@21` |
| Python | **3.12**, managed by [`uv`](https://docs.astral.sh/uv/) — your system Python is untouched |
| Gradle | Not needed — `./gradlew` downloads Gradle 8.14.3 itself |
| Minecraft | Not needed separately — the Gradle dev client downloads Minecraft 1.21.1 |
| RAM | ~2–3 GB for the game + ~2 GB for training; run only one game instance on 8 GB |

---

## Setup

```bash
git clone https://github.com/parzivalchik/minecraft-diamond-rl.git
cd minecraft-diamond-rl
```

Python environment (creates `python/.venv` with Python 3.12, PyTorch, Stable-Baselines3, …):

```bash
brew install uv
cd python && uv python install 3.12 && uv sync
```

The first `./gradlew` run in `mod/` downloads Minecraft, mappings and Fabric — allow several
minutes.

### The training world

The bot trains in a singleplayer world named **`rl_arena`**. The easiest way is to let the mod
create and open it automatically:

```bash
cd mod && ./gradlew runClient -Pmcrl.autoWorld=true
```

At the title screen the mod opens `rl_arena`, creating it first if it doesn't exist (Survival,
commands on, default Superflat). The arena is built at y = 100 inside its own bedrock box, so the
terrain underneath doesn't matter.

<details>
<summary>Creating the world by hand instead</summary>

1. `cd mod && ./gradlew runClient`
2. Singleplayer → Create New World → name it **rl_arena**, Game Mode **Survival**, Allow Commands **On**.
3. More → World Type **Superflat** → Customize → Presets → **The Void** → Use Preset → Create New World.
</details>

---

## Quick start

Terminal 1 — the game (opens the world automatically, runs at 200 ticks/sec while training):

```bash
cd mod && ./gradlew runClient -Pmcrl.autoWorld=true -Pmcrl.tickRate=200
```

Terminal 2 — check everything works (about a minute):

```bash
cd python && uv run python smoke.py
```

Terminal 2 — start training:

```bash
cd python && caffeinate -dis uv run python train.py --run my-first-run
```

Optional — watch the learning curves at http://localhost:6006:

```bash
cd python && uv run tensorboard --logdir runs
```

---

## Training

```bash
uv run python train.py --run NAME [--resume] [--total-steps N] [--port P]
```

| Option | Meaning |
|---|---|
| `--run NAME` | Run name; everything goes to `python/runs/NAME/` (checkpoints, TensorBoard logs, curriculum state). |
| `--resume` | Continue from the newest readable checkpoint and the saved curriculum stage. |
| `--total-steps N` | Steps to train in this invocation (default 5,000,000). On `--resume` this is **added** to the steps already trained. |
| `--port P` | Bridge port (default 5005); must match the game's `-Pmcrl.port`. |

**What gets saved** in `python/runs/NAME/`:

- `checkpoints/ppo_<steps>_steps.zip` — every 20,000 steps, plus a final save on Ctrl-C.
- `curriculum.json` — current stage and the last 100 episode outcomes.
- `tb/` — TensorBoard logs.

**Stopping and resuming**

- Ctrl-C in the training terminal saves a checkpoint and exits cleanly. If the trainer was started
  detached in the background, Ctrl-C can't reach it; stopping it then loses at most 20,000 steps.
- Continue with `uv run python train.py --run NAME --resume`.
- Reusing a run name *without* `--resume` is refused, so a new run never mixes with old checkpoints.
- If the newest checkpoint is corrupt, resume falls back to the next older one with a warning.

**Resilience during long runs**

- If the game crashes or restarts, the trainer reconnects with backoff (0.5 s → 10 s) and carries on;
  the interrupted episode is discarded and not counted.
- Opening the pause menu just stalls training until you close it.
- `caffeinate -dis` keeps the Mac and display awake; macOS sleep would pause everything.

**TensorBoard metrics worth watching**

| Metric | What it tells you |
|---|---|
| `curriculum/stage` | Which arena the agent is on (0–4). The headline progress number. |
| `curriculum/success_rate` | Success over the last 100 episodes; 0.6 advances the stage. |
| `episode/diamonds` | Diamonds broken per episode. |
| `episode/death_rate`, `death_cause/*` | How often and how the agent dies (almost always lava). |
| `rollout/ep_rew_mean` | Average episode reward. |
| `train/entropy_loss` | Policy randomness; a fast slide toward 0 means the policy is collapsing onto one action. |
| `time/fps` | Environment steps per second. |

---

## Watching and checking the agent

Watch a trained agent play (no learning; uses the newest checkpoint unless `--checkpoint` is given):

```bash
cd python && uv run python watch.py --run NAME --episodes 5
```

Live smoke test of the whole stack — run it whenever something seems off:

```bash
cd python && uv run python smoke.py
```

It checks: frames are 64×64 and not black, the arena has the right diamonds, the player starts
healthy, 200 random steps all return frames, throughput ≥ 20 steps/sec, the same seed builds the
same arena, holding attack breaks blocks, the tunnel action breaks blocks and moves the player, and
unknown stages are rejected. Each check prints `PASS`/`FAIL`; exit code 1 on any failure.

---

## Configuration

Game flags (pass to `./gradlew runClient`):

| Flag | Default | Effect |
|---|---|---|
| `-Pmcrl.autoWorld=true` | off | Create/open the `rl_arena` world automatically at the title screen. |
| `-Pmcrl.tickRate=200` | 100 | Game ticks per second while an agent is connected. 200 roughly doubles throughput on an M2 (≈ 42 vs 21 steps/sec in the smoke test). |
| `-Pmcrl.port=5006` | 5005 | Bridge port; pass the same `--port` to the Python scripts. |

The game window is small (320×240) and render distance minimal on purpose — frames are downscaled
to 64×64 anyway.

Training hyperparameters live in `python/mcrl/training.py` (`PPO_HYPERPARAMS`), reward weights in
`python/mcrl/rewards.py` (`RewardConfig`), and arena stages in
`mod/src/main/java/dev/mcrl/arena/StageConfig.java`.

---

## The environment

### Actions

`Discrete(13)`:

| # | Action | # | Action |
|---|---|---|---|
| 0 | no-op | 7 | turn right 15° |
| 1 | forward | 8 | look up 15° |
| 2 | back | 9 | look down 15° |
| 3 | strafe left | 10 | attack (mine) |
| 4 | strafe right | 11 | forward + attack |
| 5 | jump + forward | 12 | **tunnel forward** |
| 6 | turn left 15° | | |

Normal actions last 4 game ticks. Attack is held across consecutive attacking steps, so mining
progress carries over (vanilla would reset it).

**Tunnel forward** turns to the nearest cardinal direction, breaks the block ahead at foot and head
level, and walks one block into the gap. It costs the **real** mining time with an iron pickaxe
(stone ≈ 8 ticks, deepslate/ores ≈ 15), so it is a shortcut for the *decision*, not a cheat on
time. Bedrock blocks it; lava is not broken and walking into it is the agent's risk.
It exists because tunnelling sideways with actions 0–11 needs ~6 perfectly coordinated steps per
block, which the agent never discovered on its own (see [Results](#results-so-far)).

### Observations

```python
{
  "image": Box(0, 255, (64, 64, 12), uint8),   # last 4 RGB frames stacked on the channel axis
  "state": Box(0, 1, (3,), float32),           # [health/20, food/20, on_fire]
}
```

Privileged information (diamond distance, position) is used **only** for the reward, never shown
to the policy. The player has permanent Night Vision so dug tunnels aren't pitch black, and blocks
drop no items.

### Rewards

| Event | Reward |
|---|---|
| Diamond ore broken | **+10** |
| Stone / deepslate / coal / iron broken | +0.05 each, **shared cap of 20 blocks per episode** |
| Getting closer to the nearest diamond (new minimum distance this episode) | **+1.0 per block** |
| Damage taken | −0.5 per heart |
| In lava or on fire (once per step) | −2 |
| Death | −10, episode ends |
| Every step | −0.001 |

An episode ends on death, when every diamond in the arena is broken, or after **1,000 steps**.
*Success* = at least one diamond broken and no death.

### Curriculum

| Stage | Arena (x×y×z) | Diamonds | Guaranteed near spawn | Lava pockets |
|---|---|---|---|---|
| 0 | 7×4×7 | 4–5 | 2 within 3 blocks | 0 |
| 1 | 9×5×9 | 3–4 | 1 within 5 blocks | 0 |
| 2 | 11×6×11 | 3 | 1 within 7 blocks | 1 |
| 3 | 15×8×15 | 2–3 | — | 1–2 |
| 4 | 25×12×25 | 1–2 | — | 3–4 |

Every arena is a random mix (~85% stone/deepslate, coal, iron, gravel, short air shafts) inside a
bedrock box, rebuilt from a new seed each episode. The agent starts in a 3×3 pit at the top
centre holding an unbreakable iron pickaxe. It advances to the next stage when **≥ 60% of the last
100 episodes succeed**; stages never go back down.

---

## Results so far

Three iterations, each fixing what the previous one revealed:

| Run | Setup | What happened |
|---|---|---|
| `diamond1` | 12 actions, 15×15 arena from the start, ore rewards +0.5/+1 | First diamond after 32 min, but **flat at ~5% success for 750k steps**. Diagnosis: the policy collapsed onto "stand still and attack", farming coal/iron near spawn; it never learned to tunnel. |
| `diamond2` | Added stage 0, ore reward → 0.05, closer-to-diamond reward 0.2 → 1.0 | Learned stage 0 (5% → 60% in ~700k steps), then **stalled at ~6% on the 15×15 arena** — diamonds 5–10 blocks away need sideways tunnelling. |
| `diamond4` | Added the tunnel action, stages 1–2 as stepping stones, ore shares the block cap, 200 ticks/sec | **Passed stage 0 in ~70 min and stage 1 in ~85 min (97–100% success)**. On stage 2 (first lava) it still finds ~0.8 diamonds per episode but dies in lava in ~50% of episodes; 24% success at 264k steps when paused. |

Next likely step if stage 2 stays stuck: stop the tunnel action from stepping forward into lava,
the way a player would stop on seeing it.

---

## Project layout

```
mod/                                   Fabric 1.21.1 client mod (Java 21)
  src/main/java/dev/mcrl/
    McrlClient.java                    entrypoint: wires the bridge, events, render settings
    StepController.java                client-tick state machine: one action → N ticks → frame
    Actions.java                       the 13 actions
    FrameCapture.java                  framebuffer → 64×64 RGB
    EpisodeTracker.java                buffers block-broken / damage / death events per step
    AutoWorld.java                     opt-in create/open of the rl_arena world
    RenderSettings.java                cheap render options for the training client
    arena/ArenaGenerator.java          pure (seed, stage) → arena layout (unit-tested)
    arena/ArenaManager.java            writes arenas into the world, resets the player, observes
    arena/StageConfig.java             the curriculum stage table
    arena/TunnelPlan.java, Tunneler.java   the tunnel-forward action
    bridge/RlBridgeServer.java         localhost TCP server (one agent at a time)
    bridge/Protocol.java               framing + request validation
    mixin/MinecraftClientMixin.java    hands block breaking to StepController; 100–200 Hz client ticks
python/
  mcrl/protocol.py                     TCP client with reconnect/backoff
  mcrl/env.py                          Gymnasium MinecraftEnv (frame stacking, episode logic)
  mcrl/rewards.py                      RewardConfig + RewardTracker (pure)
  mcrl/curriculum.py                   stage advancement, atomic save/load
  mcrl/training.py                     PPO construction, checkpoints, TensorBoard metrics
  train.py / watch.py / smoke.py       entry points
  tests/                               pytest suite incl. a fake game server
docs/design.md                         design spec
```

---

## Wire protocol

Every message is a 4-byte big-endian length followed by the payload, over `127.0.0.1:5005`.

Requests (JSON):

```json
{"cmd": "reset", "seed": 1234, "stage": 0}
{"cmd": "step",  "action": 12}
{"cmd": "close"}
```

Reply to `reset`/`step`: a JSON header message, then a frame message of 64×64×3 = 12,288 raw RGB
bytes (row-major, top-left origin):

```json
{
  "health": 20.0, "food": 20, "on_fire": false,
  "x": 8.5, "y": 103.0, "z": 8.5, "yaw": 0.0, "pitch": 30.0,
  "nearest_diamond_dist": 3.1, "diamonds_remaining": 5,
  "events": [{"type": "block_broken", "block": "minecraft:stone"},
             {"type": "damage", "source": "lava", "amount": 4.0}],
  "dead": false, "tick": 1532
}
```

Errors come back as `{"error": "..."}` with an empty frame (e.g. no world open, unknown stage).

---

## Tests

```bash
cd python && uv run pytest        # 70 tests, no game needed (uses a fake bridge server)
```
```bash
cd mod && ./gradlew test          # 79 JUnit tests (arena generator, protocol, bridge, tunnel math, …)
```

Plus the live `smoke.py` check above, which needs the game running.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `BridgeError: no singleplayer world loaded - open the rl_arena world` | The game is on the title screen. Start it with `-Pmcrl.autoWorld=true` or open `rl_arena` by hand. |
| Trainer prints "retrying" messages forever | The game isn't running or is still loading; start it and the trainer reconnects by itself. |
| `run 'X' already has checkpoints … pass --resume` | Add `--resume`, or pick a new `--run` name. |
| Steps/sec much lower than the smoke test | Expected once the agent tunnels a lot (tunnel steps take 10–36 ticks). macOS may also throttle a hidden or minimized game window — keep it visible. |
| `cannot bind MCRL bridge on port 5005` | Another game instance is still running; quit it, or use `-Pmcrl.port=5006` and `--port 5006`. |
| `--resume` on a run from before 2026-10-07 starts fresh | Those runs used 12 actions and older stage numbers; start a new run name. |

---

## Known limitations

- **One game instance.** On 8 GB RAM only one client fits; parallel environments would need more
  memory or a multi-camera mod.
- **Player physics aren't frozen between steps.** The world is tick-frozen, but the player still
  ticks while Python is busy (most noticeably during PPO updates), so fire/fall damage can drift
  slightly between steps.
- **Stages 3–4 untested in training so far**, and a real-terrain stage (beyond the bedrock arenas) is
  not implemented.
- **Falling blocks** (gravel mid-fall) can survive an arena reset and appear floating in frames.
- **The tunnel action walks blindly** for its last 6 ticks, which is the main source of lava deaths
  on stage 2.
