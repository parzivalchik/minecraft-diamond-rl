import pytest

from mcrl.rewards import RewardConfig, RewardTracker
from tests.fake_bridge import make_header

STEP = RewardConfig().step


def broken(block):
    return {"type": "block_broken", "block": block}


def damage(source, amount):
    return {"type": "damage", "source": source, "amount": amount}


def tracker(**reset_overrides):
    t = RewardTracker()
    t.reset(make_header(**reset_overrides))
    return t


def test_idle_step_costs_step_penalty():
    reward, info = tracker().compute(make_header())
    assert reward == pytest.approx(STEP)
    assert info == {"diamonds": 0}


@pytest.mark.parametrize("block,expected", [
    ("minecraft:diamond_ore", 10.0),
    ("minecraft:deepslate_diamond_ore", 10.0),
    ("minecraft:iron_ore", 1.0),
    ("minecraft:deepslate_iron_ore", 1.0),
    ("minecraft:coal_ore", 0.5),
    ("minecraft:deepslate_coal_ore", 0.5),
    ("minecraft:stone", 0.05),
    ("minecraft:deepslate", 0.05),
    ("minecraft:gravel", 0.0),
])
def test_block_rewards(block, expected):
    reward, _ = tracker().compute(make_header(events=[broken(block)]))
    assert reward == pytest.approx(expected + STEP)


def test_diamond_counted_in_info():
    _, info = tracker().compute(make_header(events=[broken("minecraft:diamond_ore")]))
    assert info["diamonds"] == 1


def test_stone_reward_capped_at_20_per_episode():
    t = tracker()
    total = sum(t.compute(make_header(events=[broken("minecraft:stone")]))[0] for _ in range(25))
    assert total == pytest.approx(20 * 0.05 + 25 * STEP)


def test_stone_cap_resets_each_episode():
    t = tracker()
    for _ in range(20):
        t.compute(make_header(events=[broken("minecraft:stone")]))
    t.reset(make_header())
    reward, _ = t.compute(make_header(events=[broken("minecraft:stone")]))
    assert reward == pytest.approx(0.05 + STEP)


def test_damage_penalty_per_heart():
    reward, _ = tracker().compute(make_header(events=[damage("fall", 4.0)]))
    assert reward == pytest.approx(-0.5 * 2 + STEP)


def test_fire_penalty_applied_once_per_step():
    reward, _ = tracker().compute(make_header(events=[damage("lava", 4.0), damage("on_fire", 1.0)]))
    assert reward == pytest.approx(-0.5 * 2.5 - 2.0 + STEP)


def test_death_penalty():
    reward, _ = tracker().compute(make_header(dead=True))
    assert reward == pytest.approx(-10.0 + STEP)


def test_distance_reward_only_on_new_minimum():
    t = tracker(nearest_diamond_dist=10.0)
    r1, _ = t.compute(make_header(nearest_diamond_dist=8.0))
    r2, _ = t.compute(make_header(nearest_diamond_dist=9.0))   # moved away: nothing
    r3, _ = t.compute(make_header(nearest_diamond_dist=8.5))   # closer than 9 but not than 8
    r4, _ = t.compute(make_header(nearest_diamond_dist=7.0))
    assert r1 == pytest.approx(0.2 * 2 + STEP)
    assert r2 == pytest.approx(STEP)
    assert r3 == pytest.approx(STEP)
    assert r4 == pytest.approx(0.2 * 1 + STEP)


def test_distance_baseline_resets_when_a_diamond_is_taken():
    t = tracker(nearest_diamond_dist=3.0, diamonds_remaining=2)
    # Diamond broken: nearest jumps to the next diamond, 9 blocks away. No shaping reward this step.
    r1, _ = t.compute(make_header(events=[broken("minecraft:diamond_ore")],
                                  nearest_diamond_dist=9.0, diamonds_remaining=1))
    r2, _ = t.compute(make_header(nearest_diamond_dist=7.0, diamonds_remaining=1))
    assert r1 == pytest.approx(10.0 + STEP)
    assert r2 == pytest.approx(0.2 * 2 + STEP)


def test_no_distance_reward_when_no_diamonds_left():
    t = tracker(nearest_diamond_dist=-1.0, diamonds_remaining=0)
    reward, _ = t.compute(make_header(nearest_diamond_dist=-1.0, diamonds_remaining=0))
    assert reward == pytest.approx(STEP)
