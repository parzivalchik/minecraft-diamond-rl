"""Stage progression by rolling success rate (spec §7)."""
from __future__ import annotations

import contextlib
import json
import os
import tempfile
import warnings
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Curriculum:
    stage: int = 1
    max_stage: int = 2
    threshold: float = 0.6
    window: int = 100
    history: deque = field(default_factory=deque)

    def record(self, success: bool) -> bool:
        """Record one finished episode. Returns True if this advanced the stage."""
        self.history.append(bool(success))
        while len(self.history) > self.window:
            self.history.popleft()
        if (self.stage < self.max_stage
                and len(self.history) == self.window
                and self.success_rate() >= self.threshold):
            self.stage += 1
            self.history.clear()
            return True
        return False

    def success_rate(self) -> float:
        return sum(self.history) / len(self.history) if self.history else 0.0

    def to_dict(self) -> dict:
        return {"stage": self.stage, "history": list(self.history)}

    @classmethod
    def from_dict(cls, data: dict) -> "Curriculum":
        c = cls(stage=int(data["stage"]))
        c.history.extend(bool(x) for x in list(data["history"])[-c.window:])
        return c

    def save(self, path) -> None:
        """Write atomically (temp file in the same directory, then os.replace), so a crash or
        Ctrl-C mid-save never leaves a truncated curriculum file behind."""
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
        try:
            with os.fdopen(fd, "w") as f:
                f.write(json.dumps(self.to_dict()))
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, path)
        except BaseException:
            with contextlib.suppress(FileNotFoundError):
                os.unlink(tmp)
            raise

    @classmethod
    def load(cls, path) -> "Curriculum":
        try:
            return cls.from_dict(json.loads(Path(path).read_text()))
        except FileNotFoundError:
            return cls()
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as e:
            warnings.warn(f"ignoring unreadable curriculum file {path}: {e}", UserWarning)
            return cls()
