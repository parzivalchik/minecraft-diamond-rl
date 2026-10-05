import pytest

from tests.fake_bridge import FakeBridge


@pytest.fixture
def bridge():
    fake = FakeBridge()
    yield fake
    fake.close()
