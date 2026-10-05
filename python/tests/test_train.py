import pytest

import train


def test_existing_run_without_resume_exits(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    checkpoints = tmp_path / "runs" / "diamond1" / "checkpoints"
    checkpoints.mkdir(parents=True)
    (checkpoints / "ppo_20000_steps.zip").touch()
    with pytest.raises(SystemExit) as exc:
        train.main(["--run", "diamond1"])
    message = str(exc.value.code)
    assert "--resume" in message and "diamond1" in message


def test_total_steps_help_says_additive_on_resume(capsys):
    with pytest.raises(SystemExit):
        train.main(["--help"])
    assert "added" in capsys.readouterr().out
