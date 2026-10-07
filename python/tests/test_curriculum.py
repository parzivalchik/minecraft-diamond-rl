import os

import pytest

from mcrl.curriculum import Curriculum


def test_starts_at_stage_0():
    assert Curriculum().stage == 0


def test_does_not_advance_before_window_is_full():
    c = Curriculum(window=10)
    for _ in range(9):
        assert c.record(True) is False
    assert c.stage == 0


def test_advances_at_threshold_and_clears_history():
    c = Curriculum(window=10, threshold=0.6)
    for success in [True] * 5 + [False] * 4:
        c.record(success)
    assert c.record(True) is True  # 6/10 = 0.6 ≥ threshold
    assert c.stage == 1
    assert len(c.history) == 0


def test_below_threshold_stays():
    c = Curriculum(window=10, threshold=0.6)
    for success in [True] * 5 + [False] * 5:
        c.record(success)
    assert c.stage == 0


def test_window_slides():
    c = Curriculum(window=10)
    for _ in range(10):
        c.record(False)
    for _ in range(5):
        c.record(True)
    assert len(c.history) == 10
    assert c.success_rate() == pytest.approx(0.5)


def test_default_max_stage_is_4():
    assert Curriculum().max_stage == 4
    c = Curriculum(window=2, stage=3)
    c.record(True)
    assert c.record(True) is True
    assert c.stage == 4
    c.record(True)
    assert c.record(True) is False
    assert c.stage == 4


def test_from_dict_keeps_default_max_stage():
    assert Curriculum.from_dict({"stage": 3, "history": []}).max_stage == 4


def test_never_exceeds_max_stage():
    c = Curriculum(window=2, max_stage=2, stage=2)
    c.record(True)
    assert c.record(True) is False
    assert c.stage == 2


def test_save_load_round_trip(tmp_path):
    c = Curriculum(window=10)
    c.record(True)
    c.record(False)
    path = tmp_path / "curriculum.json"
    c.save(path)
    loaded = Curriculum.load(path)
    assert loaded.stage == 0
    assert list(loaded.history) == [True, False]


def test_load_missing_file_returns_default(tmp_path):
    assert Curriculum.load(tmp_path / "nope.json").stage == 0


def test_load_corrupt_file_falls_back_to_stage_0(tmp_path):
    path = tmp_path / "curriculum.json"
    path.write_text("{not json")
    with pytest.warns(UserWarning, match="unreadable curriculum"):
        c = Curriculum.load(path)
    assert c.stage == 0


def test_save_is_atomic(tmp_path, monkeypatch):
    path = tmp_path / "curriculum.json"
    Curriculum(stage=2).save(path)

    def crash(src, dst):
        raise OSError("disk full")
    monkeypatch.setattr(os, "replace", crash)
    with pytest.raises(OSError):
        Curriculum(stage=1).save(path)
    assert Curriculum.load(path).stage == 2  # the old file survived the failed save
    assert [p.name for p in tmp_path.iterdir()] == ["curriculum.json"]  # no temp file left behind


def test_save_overwrites(tmp_path):
    path = tmp_path / "curriculum.json"
    Curriculum(stage=1).save(path)
    Curriculum(stage=2).save(path)
    assert Curriculum.load(path).stage == 2
    assert [p.name for p in tmp_path.iterdir()] == ["curriculum.json"]
