"""Length-prefixed TCP client for the MCRL Fabric bridge (spec §4)."""
from __future__ import annotations

import json
import socket
import struct
import time
from dataclasses import dataclass

import numpy as np

FRAME_SIZE = 64
FRAME_BYTES = FRAME_SIZE * FRAME_SIZE * 3
MAX_MESSAGE = 16 * 1024 * 1024


class BridgeError(RuntimeError):
    """The mod answered a request with an error reply."""


def send_msg(sock: socket.socket, payload: bytes) -> None:
    sock.sendall(struct.pack(">I", len(payload)) + payload)


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("bridge closed the connection")
        buf.extend(chunk)
    return bytes(buf)


def recv_msg(sock: socket.socket) -> bytes:
    (length,) = struct.unpack(">I", recv_exact(sock, 4))
    if length > MAX_MESSAGE:
        raise ConnectionError(f"message too large: {length} bytes")
    return recv_exact(sock, length)


@dataclass
class Reply:
    header: dict
    frame: np.ndarray | None


class BridgeClient:
    def __init__(self, host: str = "127.0.0.1", port: int = 5005,
                 max_backoff: float = 10.0, timeout: float = 30.0):
        self.host = host
        self.port = port
        self.max_backoff = max_backoff
        self.timeout = timeout
        self._sock: socket.socket | None = None

    def connect(self, attempts: int | None = None) -> None:
        """Connect with exponential backoff (0.5 s doubling up to max_backoff).

        attempts=None retries forever, which is what training wants while the game restarts.
        """
        delay = 0.5
        tries = 0
        while True:
            try:
                sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                self._sock = sock
                return
            except OSError:
                tries += 1
                if attempts is not None and tries >= attempts:
                    raise
                time.sleep(delay)
                delay = min(delay * 2, self.max_backoff)

    def request(self, msg: dict) -> Reply:
        if self._sock is None:
            self.connect()
        try:
            send_msg(self._sock, json.dumps(msg).encode())
            header = json.loads(recv_msg(self._sock))
            frame_bytes = recv_msg(self._sock)
        except OSError as e:
            self._drop()
            raise ConnectionError(f"bridge connection lost: {e}") from e
        if "error" in header:
            raise BridgeError(header["error"])
        frame = None
        if frame_bytes:
            if len(frame_bytes) != FRAME_BYTES:
                self._drop()
                raise ConnectionError(f"bad frame size {len(frame_bytes)}")
            frame = np.frombuffer(frame_bytes, dtype=np.uint8).reshape(FRAME_SIZE, FRAME_SIZE, 3)
        return Reply(header=header, frame=frame)

    def close(self) -> None:
        if self._sock is not None:
            try:
                send_msg(self._sock, json.dumps({"cmd": "close"}).encode())
            except OSError:
                pass
            self._drop()

    def _drop(self) -> None:
        if self._sock is not None:
            try:
                self._sock.close()
            finally:
                self._sock = None
