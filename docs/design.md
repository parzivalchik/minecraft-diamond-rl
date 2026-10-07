# Minecraft Diamond-Finding RL Bot — Design

Date: 2026-10-05
Status: Draft for review

## 1. Goal

Train a reinforcement-learning agent that, **from screen pixels**, learns to mine
toward and break diamond ore **without dying**. The system consists of a Fabric
mod (the environment) and a Python training stack (the learner).

### Success criteria

- Stage 3 (the 15×15 arena; called stage 1 before 2026-10-07): ≥ 60% of the last 100 episodes
  break at least one diamond ore with no death.
- The training run can be stopped and resumed from checkpoints without loss.
- The environment sustains ≥ 20 steps/sec on the target machine.

### Constraints

- Target machine: Apple M2, 8 GB RAM → exactly **one** Minecraft client at a time.
- Minecraft **1.21.1**, **Fabric**, Java 21.
- Python **3.12** in a project-local `uv` virtual environment (system Python 3.14 untouched).
- Expected training time: 1–3 days of background running to reach the stage-3 criterion.

### Non-goals (v1)

- Crafting, tech-tree progression, inventory management, block placing.
- Multiple parallel game instances.
- Multiplayer / dedicated server support.

## 2. Architecture

```
┌───────────────────────────── Minecraft client (Fabric 1.21.1) ─────────────────────────────┐
│  RlBridgeServer (TCP :5005, bg thread) ──queue──► StepController (client tick)              │
│                                                     │  applies input, holds N ticks         │
│                                                     ▼                                       │
│                                    FrameCapture (64×64 RGB)   ArenaManager (integrated srv) │
└────────────────────────────────────────────────────────────────────────────────────────────┘
                     ▲  length-prefixed messages over localhost TCP
                     ▼
┌──────────────────────────── Python 3.12 (uv) ─────────────────────────────┐
│ protocol.py ◄── env.py (Gymnasium MinecraftEnv) ◄── train.py (SB3 PPO)    │
│                   │                                                       │
│                   ├── rewards.py (pure: events → reward)                  │
│                   └── curriculum.py (success rate → stage)                │
└───────────────────────────────────────────────────────────────────────────┘
```

Responsibility split: **the mod reports raw facts (frames, state, events); Python
decides rewards, curriculum, and learning.** Reward tuning never requires a mod rebuild.

## 3. Mod components

### 3.1 RlBridgeServer
- TCP server on `localhost:5005`, single client, on a background thread.
- Reads framed requests, enqueues them for the game thread; never touches game state directly.
- Writes replies produced by the game thread back to the socket.
- On malformed request: replies `{"error": "<message>"}`, keeps running.
- On disconnect: returns to accepting connections; the game idles.

### 3.2 StepController
- Runs on the client tick (`ClientTickEvents.END_CLIENT_TICK`).
- On a `step` request: applies the action's key states / yaw-pitch delta, advances
  **4 game ticks** (frame skip), then triggers capture and replies.
- **Attack is held continuously across consecutive attacking steps** (actions 10, 11)
  so block-breaking progress is not reset between steps. Attack is released when the
  next action does not attack.
- **Action 12 (tunnel_forward)** is a macro step whose length varies: the server breaks the 1×2
  blocks ahead and the step lasts their survival break time plus a short walk (see §5); server
  and client still advance the same number of ticks.
- Between steps the game does not advance (integrated server paused / tick-frozen),
  so Python compute time never desyncs the simulation.
- Pitch is clamped to [-90°, 90°].

### 3.3 FrameCapture
- After world render, reads the main framebuffer, downsamples to **64×64 RGB**
  (12,288 bytes), row-major, top-left origin.
- Client configured for cheap rendering: window ~320×240, render distance 2,
  clouds/particles off, HUD and hand hidden (`F1`-equivalent) so frames contain only the world.

### 3.4 ArenaManager
- On startup, creates or loads a void/flat world named `rl_arena` automatically.
- On `reset(seed, stage)`:
  1. Clears the arena region.
  2. Generates a fresh layout from `seed` (deterministic for a given seed + stage).
  3. Teleports the player to spawn, restores health/hunger, clears status effects
     and fire, sets inventory to a single **iron pickaxe** in the selected slot.
- Tracks per-step events on the integrated server: blocks broken (with id), damage
  taken (with source), death.
- Computes `nearest_diamond_dist`: Euclidean distance from the player's eye
  position to the nearest remaining diamond ore block in the arena (−1 if none).

## 4. Protocol

Framing: every message = 4-byte big-endian length + payload.

### Requests (JSON)
```json
{"cmd": "reset", "seed": 1234, "stage": 1}
{"cmd": "step",  "action": 7}
{"cmd": "close"}
```

### Reply to `reset` and `step`
```
[4B header len][JSON header][4B frame len][64×64×3 RGB bytes]
```
```json
{
  "health": 20.0,
  "food": 20,
  "on_fire": false,
  "x": 4.5,
  "y": -58.0,
  "z": 7.7,
  "yaw": 90.0,
  "pitch": 15.0,
  "nearest_diamond_dist": 6.4,
  "diamonds_remaining": 2,
  "events": [
    {"type": "block_broken", "block": "minecraft:diamond_ore"},
    {"type": "damage", "source": "lava", "amount": 4.0}
  ],
  "dead": false,
  "tick": 1532
}
```
`events` contains only what happened during that step (empty list on `reset`).
`x` and `z` (player position, added 2026-10-07) are, like `y`, for tests and diagnostics only —
never part of the observation.

### Error reply
`{"error": "<message>"}` with a frame length of 0.

### Python-side resilience
Socket drop → reconnect with exponential backoff (0.5 s → max 10 s); the in-progress
episode is discarded and the env performs a fresh `reset`.

## 5. Action space (Discrete(13))

| # | Action           | # | Action           |
|---|------------------|---|------------------|
| 0 | no-op            | 7 | turn right 15°   |
| 1 | forward          | 8 | look up 15°      |
| 2 | back             | 9 | look down 15°    |
| 3 | strafe left      | 10| attack (mine)    |
| 4 | strafe right     | 11| forward + attack |
| 5 | jump + forward   | 12| tunnel forward   |
| 6 | turn left 15°    |   |                  |

**Action 12, tunnel forward (added 2026-10-07).** Reaching a diamond 5–10 blocks away needs
sideways tunnelling, which with actions 0–11 takes ~6 coordinated look/attack/move steps per block;
the stage-0 agent of run diamond2 never learned that and stalled at ~6% on the 15×15 arena.
One tunnel step:
- snaps yaw to the nearest cardinal (N/E/S/W), pitch unchanged, and centres the player sideways in
  its block column so the 0.6-wide hitbox fits the tunnel;
- breaks (no drops) the solid blocks directly ahead at foot and head level, each reported as one
  `block_broken` event; air and fluids (lava, water) are left alone — walking into lava is the
  agent's risk, as with plain movement;
- lasts `max(4, breakTicks(head) + breakTicks(feet) + 6)` game ticks, where
  `breakTicks = ceil(1 / per-tick survival break progress)` with the held iron pickaxe (stone 8,
  deepslate / ores 15, gravel 18, deepslate diamond ore 23): the player stands still for the break
  time, then holds forward for the last 6 ticks (~1 block);
- if either target is unbreakable (bedrock): does nothing and costs a normal 4-tick step.

The mapping lives in the mod; Python sends only the index. Python keeps a matching
name table for logging.

## 6. Observation space

`gymnasium.spaces.Dict`:
- `image`: `Box(0, 255, (64, 64, 12), uint8)` — last 4 RGB frames stacked on channel axis.
- `state`: `Box(0, 1, (3,), float32)` — `[health/20, food/20, on_fire]`.

`nearest_diamond_dist`, position, and other privileged info are used **only for
reward shaping**, never exposed to the policy.

## 7. Arena and curriculum

### Stages (renumbered 2026-10-07)

| Stage | Interior (x×y×z) | Diamonds | Near-spawn diamonds | Near radius | Lava |
|---|---|---|---|---|---|
| 0 | 7×4×7 | 4–5 | 2 | 3 | 0 |
| 1 | 9×5×9 | 3–4 | 1 | 5 | 0 |
| 2 | 11×6×11 | 3 | 1 | 7 | 1 |
| 3 | 15×8×15 | 2–3 | 0 | – | 1–2 |
| 4 | 25×12×25 | 1–2 | 0 | – | 3–4 |

"Near-spawn" diamonds lie within the stage's radius of the spawn feet position. All stages use the
same fill mix and enclosure rules (below).

History: stage 0 (7×7, added 2026-10-06) came after the first 750k-step run plateaued at ~5%
success — the agent must find diamonds often enough to learn that they pay before it has to search.
On 2026-10-07 run diamond2 had learned stage 0 but sat at ~6% on the 15×15 arena, whose diamonds
are 5–10 blocks away; stages 1–2 were inserted as intermediate steps (each with one diamond a short
tunnel from spawn), and together with the tunnel action (§5) bridge that gap. The former stages
1 and 2 are now stages 3 and 4 (unchanged apart from their number), so curriculum files and
checkpoints from earlier runs do not carry over.

### Stage 3 (15×15, formerly stage 1)
- 15×15×8 volume of random blocks, enclosed by bedrock walls and floor.
- Spawn: 3×3×2 air pocket at top-center, facing slightly downward.
- Fill distribution (by volume, reshuffled per episode):

| Block | Share |
|---|---|
| stone / deepslate | ~85% |
| coal ore | ~5% |
| iron ore | ~3% |
| gravel | ~3% |
| small air pockets (2–3 deep) | ~3% |
| diamond ore | exactly 2–3 blocks |
| lava pockets | 1–2 per arena, each ≥ 3 blocks from spawn |

Diamond ore and lava pockets are placed explicitly (not by share); the remaining
volume is filled by the shares above.

### Stage 4 (formerly stage 2)
25×25×12 volume, 1–2 diamond ores, 3–4 lava pockets, same enclosure rules.

### Future: real terrain
Real generated overworld terrain; spawn at Y −55 inside a pre-carved 3×3×2 pocket.
(Deferred to a later spec; v1 implements stages 0–4. This was "stage 3" before 2026-10-07.)

### Advancement
Advance when ≥ 60% of the last 100 episodes at the current stage end with ≥ 1 diamond
broken and no death. Stage never regresses automatically.

### Episode termination
Death, all diamonds in the arena broken, or 1,000 steps (truncation).

## 8. Rewards (`rewards.py`, pure function)

| Event | Reward |
|---|---|
| `diamond_ore` / `deepslate_diamond_ore` broken | +10 |
| iron ore (incl. deepslate variant) broken | +0.05 (was +1; ore farming crowded out diamonds) |
| coal ore (incl. deepslate variant) broken | +0.05 (was +0.5) |
| stone / deepslate broken | +0.05, only for the first 20 per episode |
| new minimum `nearest_diamond_dist` this episode | +1.0 × blocks closer (was +0.2) |
| damage taken | −0.5 × hearts lost (amount / 2) |
| damage with source `lava`, `in_fire`, or `on_fire` | additional −2 per step it occurs |
| death | −10 (episode ends) |
| every step | −0.001 |

All weights live in one config dataclass so they can be tuned without code changes
to the function.

## 9. Training

- Stable-Baselines3 **PPO**, `MultiInputPolicy` (NatureCNN for `image`, MLP for `state`, concatenated).
- Device: `mps` (fallback `cpu`).
- Initial hyperparameters: `n_steps=1024`, `batch_size=256`, `learning_rate=2.5e-4`,
  `ent_coef=0.01`, `gamma=0.995`, `gae_lambda=0.95`, `clip_range=0.2`, `n_epochs=4`.
- Single environment (`DummyVecEnv` with 1 env), wrapped with `VecFrameStack(4)` or equivalent.
- Checkpoints every 20k steps to `runs/<name>/checkpoints/`; `--resume` loads the latest
  checkpoint and the curriculum state (`runs/<name>/curriculum.json`).
- TensorBoard metrics: episode reward, episode length, diamonds per episode, death rate,
  death cause breakdown, current stage, steps/sec.

### Entry points
- `train.py --run <name> [--resume]`
- `watch.py --run <name> [--checkpoint <path>]` — deterministic policy, no learning.
- `smoke.py` — 200 random actions against the live game.

## 10. Repository layout

```
MCmodding/
  mod/                          # Fabric mod (Gradle, Java 21)
    src/main/java/.../rl/
      RlBridgeServer.java
      StepController.java
      FrameCapture.java
      ArenaManager.java
      ArenaGenerator.java       # pure: (seed, stage) → layout; unit-testable
      Protocol.java
    src/test/java/.../rl/
  python/
    mcrl/
      protocol.py
      env.py
      rewards.py
      curriculum.py
    train.py
    watch.py
    smoke.py
    tests/
    pyproject.toml
  docs/
```

`ArenaGenerator` is separated from `ArenaManager` so that layout generation is a pure
function testable without a running game.

## 11. Testing

**Python (pytest):**
- `rewards.py`: each event type, stone cap at 20, distance shaping only on new minimum,
  fire penalty, death.
- `protocol.py`: framing round-trip, partial reads, error reply handling.
- `env.py`: full reset/step/terminate cycle against a **fake mod server** (Python TCP
  server emitting scripted replies); frame stacking shape; reconnect behavior.
- `curriculum.py`: advancement threshold, persistence round-trip.

**Java (JUnit):**
- `ArenaGenerator`: deterministic for same seed+stage; diamond count in range; lava
  ≥ 3 blocks from spawn; spawn pocket is air; enclosure is bedrock.

**Integration:**
- `smoke.py` against the real client: frames are 12,288 bytes and not all-black,
  resets succeed, throughput ≥ 20 steps/sec.

## 12. Risks

| Risk | Mitigation |
|---|---|
| Training too slow on 8 GB M2 | 64×64 frames, frame skip 4, render distance 2, small arena; checkpoint/resume |
| Reward hacking (stone farming, hovering near diamond) | Stone cap; distance reward only on new minimum |
| Tick-freezing between steps is brittle in 1.21.1 | Use the vanilla `/tick freeze` / `tick step` mechanism on the integrated server; verify in smoke test |
| Framebuffer readback stalls the GPU | Read once per step only (not per tick); small window |
| PyTorch not available for Python 3.14 | Project-local Python 3.12 via `uv` |
