"""Live check against the running game: frames, resets, mining, tunnelling, determinism, speed."""
from __future__ import annotations

import argparse
import sys
import time

import numpy as np

from mcrl.protocol import BridgeClient, BridgeError

STEPS = 200
N_ACTIONS = 13
LOOK_DOWN, ATTACK, TUNNEL_FORWARD = 9, 10, 12
STAGE = 3  # the 15x15 arena (2-3 diamonds)

failures: list[str] = []


def check(ok: bool, label: str) -> None:
    print(("PASS " if ok else "FAIL ") + label)
    if not ok:
        failures.append(label)


def frame_ok(reply) -> bool:
    return reply.frame is not None and reply.frame.shape == (64, 64, 3)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=5005)
    args = parser.parse_args(argv)

    failures.clear()
    client = BridgeClient(port=args.port)
    try:
        client.connect(attempts=20)
        run_checks(client)
    except BridgeError as e:
        check(False, f"the mod answered with an error: {e} (is the rl_arena world open?)")
    except OSError as e:  # includes ConnectionError
        check(False, f"bridge connection on port {args.port} failed: {e} (is the game running?)")
    finally:
        client.close()
    print(f"\n{len(failures)} failure(s)" if failures else "\nall checks passed")
    return 1 if failures else 0


def run_checks(client: BridgeClient) -> None:
    reply = client.request({"cmd": "reset", "seed": 1, "stage": STAGE})
    check(frame_ok(reply), "reset returns a 64x64x3 frame")
    if frame_ok(reply):
        check(float(reply.frame.mean()) > 5, f"reset frame is not black (mean={reply.frame.mean():.1f})")
    check(reply.header["diamonds_remaining"] in (2, 3), f"stage-{STAGE} arena has 2-3 diamonds")
    check(reply.header["health"] == 20 and not reply.header["dead"], "player starts healthy")

    rng = np.random.default_rng(0)
    start = time.perf_counter()
    resets = 0
    all_frames_ok = True
    for _ in range(STEPS):
        r = client.request({"cmd": "step", "action": int(rng.integers(N_ACTIONS))})
        all_frames_ok &= frame_ok(r)
        if r.header["dead"] or r.header["diamonds_remaining"] == 0:
            client.request({"cmd": "reset", "seed": int(rng.integers(1 << 30)), "stage": STAGE})
            resets += 1
    sps = STEPS / (time.perf_counter() - start)
    check(all_frames_ok, f"all {STEPS} step frames are 64x64x3 ({resets} mid-run resets)")
    check(sps >= 20, f"throughput {sps:.1f} steps/sec >= 20")

    a = client.request({"cmd": "reset", "seed": 42, "stage": STAGE}).header
    b = client.request({"cmd": "reset", "seed": 42, "stage": STAGE}).header
    check(a["diamonds_remaining"] == b["diamonds_remaining"]
          and abs(a["nearest_diamond_dist"] - b["nearest_diamond_dist"]) < 1e-6,
          "same seed gives the same arena")

    client.request({"cmd": "reset", "seed": 7, "stage": STAGE})
    for _ in range(4):
        client.request({"cmd": "step", "action": LOOK_DOWN})
    broken = []
    for _ in range(30):
        r = client.request({"cmd": "step", "action": ATTACK})
        broken += [e["block"] for e in r.header["events"] if e["type"] == "block_broken"]
    check(len(broken) >= 2, f"holding attack while looking down breaks blocks ({broken[:4]})")

    check_tunnel(client)

    try:
        client.request({"cmd": "reset", "seed": 1, "stage": 5})
        check(False, "stage 5 is rejected")
    except BridgeError as e:
        check("not implemented" in str(e), "stage 5 is rejected with a clear error")


def check_tunnel(client: BridgeClient) -> None:
    """From the stage-0 spawn, three tunnel_forward steps cross the spawn pocket and dig two blocks deep."""
    start = client.request({"cmd": "reset", "seed": 3, "stage": 0}).header
    if "x" not in start or "z" not in start:
        check(False, "reply header has x and z (is the mod up to date?)")
        return
    broken, ticks, h = [], [], start
    for _ in range(3):
        before = h["tick"]
        h = client.request({"cmd": "step", "action": TUNNEL_FORWARD}).header
        ticks.append(h["tick"] - before)
        broken += [e["block"] for e in h["events"] if e["type"] == "block_broken"]
    moved = float(np.hypot(h["x"] - start["x"], h["z"] - start["z"]))
    check(len(broken) >= 2, f"tunnel_forward x3 breaks blocks ({len(broken)}: {broken}; ticks per step {ticks})")
    check(moved >= 2.0, f"tunnel_forward x3 moves the player {moved:.2f} blocks (>= 2)")


if __name__ == "__main__":
    sys.exit(main())
