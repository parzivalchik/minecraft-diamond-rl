import socket

import numpy as np
import pytest

from mcrl.protocol import (
    FRAME_BYTES,
    BridgeClient,
    BridgeError,
    recv_exact,
    recv_msg,
    send_msg,
)
from tests.fake_bridge import make_header


def test_send_and_receive_round_trip():
    a, b = socket.socketpair()
    with a, b:
        send_msg(a, b"hello")
        send_msg(a, b"")
        assert recv_msg(b) == b"hello"
        assert recv_msg(b) == b""


def test_recv_exact_raises_connection_error_when_peer_closes():
    a, b = socket.socketpair()
    with b:
        a.sendall(b"ab")
        a.close()
        with pytest.raises(ConnectionError):
            recv_exact(b, 4)


def test_frame_bytes_constant():
    assert FRAME_BYTES == 64 * 64 * 3


def test_request_returns_header_and_frame(bridge):
    client = BridgeClient(port=bridge.port)
    reply = client.request({"cmd": "reset", "seed": 1, "stage": 1})
    assert reply.header["health"] == 20.0
    assert reply.frame.shape == (64, 64, 3)
    assert reply.frame.dtype == np.uint8
    assert int(reply.frame[0, 0, 0]) == 1
    assert bridge.requests == [{"cmd": "reset", "seed": 1, "stage": 1}]
    client.close()


def test_error_reply_raises_bridge_error(bridge):
    bridge.respond = lambda req, n: {"error": "no singleplayer world loaded"}
    client = BridgeClient(port=bridge.port)
    with pytest.raises(BridgeError, match="no singleplayer world"):
        client.request({"cmd": "step", "action": 0})


def test_dropped_connection_raises_connection_error_and_reconnects(bridge):
    bridge.drop_after = 1
    client = BridgeClient(port=bridge.port)
    client.request({"cmd": "step", "action": 0})
    with pytest.raises(ConnectionError):
        client.request({"cmd": "step", "action": 0})
    reply = client.request({"cmd": "step", "action": 0})  # reconnects transparently
    assert reply.header == make_header()


def test_connect_gives_up_after_attempts():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]  # bound but not listening → refused
        client = BridgeClient(port=port, max_backoff=0.01, timeout=0.2)
        with pytest.raises(OSError):
            client.connect(attempts=2)
