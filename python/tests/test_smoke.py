import time

import smoke


def closed_cleanly(bridge, timeout=2.0) -> bool:
    """The fake reads the client's close message on its own thread; give it a moment."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if bridge.requests and bridge.requests[-1] == {"cmd": "close"}:
            return True
        time.sleep(0.01)
    return False


def test_error_reply_is_a_fail_line_not_a_traceback(bridge, capsys):
    bridge.respond = lambda req, n: {"error": "no singleplayer world loaded - open the rl_arena world"}
    assert smoke.main(["--port", str(bridge.port)]) == 1
    out = capsys.readouterr().out
    assert "FAIL" in out and "rl_arena" in out
    assert closed_cleanly(bridge)


def test_missing_frame_is_a_fail_line_not_a_traceback(bridge, capsys):
    bridge.send_frames = False
    assert smoke.main(["--port", str(bridge.port)]) == 1
    assert "FAIL reset returns a 64x64x3 frame" in capsys.readouterr().out
    assert closed_cleanly(bridge)
