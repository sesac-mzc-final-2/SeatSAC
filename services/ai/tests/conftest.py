import pytest
from fastapi.testclient import TestClient

from seatsac_ai.config import Settings
from seatsac_ai.main import create_app

USER = {"X-User-Id": "11111111-2222-3333-4444-555555555555"}


@pytest.fixture
def app():
    # .env·환경변수에 영향받지 않게 기본값만 쓴다
    return create_app(Settings(_env_file=None))


@pytest.fixture
def client(app):
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def user_headers():
    return dict(USER)
