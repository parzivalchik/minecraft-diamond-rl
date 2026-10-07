"""Scripted stand-in for the Fabric mod: answers reset/step over real TCP."""
from __future__ import annotations

import json
import socket
import threading
from typing import Callable

from mcrl.protocol import FRAME_BYTES, recv_msg, send_msg


def make_header(**overrides) -> dict:
    header = {
        "health": 20.0,
        "food": 20,
        "on_fire": False,
        "x": 4.5,
        "y": 100.0,
        "z": 4.5,
        "yaw": 0.0,
        "pitch": 30.0,
        "nearest_diamond_dist": 5.0,
        "diamonds_remaining": 2,
        "events": [],
        "dead": False,
        "tick": 0,
    }
    header.update(overrides)
    return header


class FakeBridge:
    def __init__(self, respond: Callable[[dict, int], dict] | None = None):
        self.respond = respond or (lambda req, n: make_header())
        self.requests: list[dict] = []
        self.drop_after: int | None = None
        self.send_frames = True  # False: reply with empty frames, like a reply with no frame attached
        self._replies = 0
        self._server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server.bind(("127.0.0.1", 0))
        self._server.listen(1)
        self.port = self._server.getsockname()[1]
        threading.Thread(target=self._serve, daemon=True).start()

    def _serve(self) -> None:
        while True:
            try:
                conn, _ = self._server.accept()
            except OSError:
                return
            with conn:
                self._handle(conn)

    def _handle(self, conn: socket.socket) -> None:
        while True:
            try:
                req = json.loads(recv_msg(conn))
            except OSError:
                return
            self.requests.append(req)
            if req["cmd"] == "close":
                return
            if self.drop_after is not None and self._replies >= self.drop_after:
                self.drop_after = None
                return  # close without replying, like a crashed game
            header = self.respond(req, len(self.requests))
            self._replies += 1
            send_msg(conn, json.dumps(header).encode())
            if "error" in header or not self.send_frames:
                frame = b""
            else:
                frame = bytes([len(self.requests) % 256]) * FRAME_BYTES
            send_msg(conn, frame)

    def close(self) -> None:
        self._server.close()
