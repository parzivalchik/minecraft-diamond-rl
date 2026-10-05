"""Stage progression by rolling success rate (spec §7)."""
from __future__ import annotations

import json
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
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.to_dict()))

    @classmethod
    def load(cls, path) -> "Curriculum":
        try:
            return cls.from_dict(json.loads(Path(path).read_text()))
        except FileNotFoundError:
            return cls()
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as e:
            warnings.warn(f"ignoring unreadable curriculum file {path}: {e}", UserWarning)
            return cls()
