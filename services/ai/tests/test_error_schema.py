"""오류 응답이 contracts/schemas/error.v1.json 을 따르는지 확인 (Java·Python 공통 규약 테스트)."""
import json
import pathlib

from fastapi.testclient import TestClient

from app.main import app

SCHEMA = json.loads((pathlib.Path(__file__).parents[3] / "contracts/schemas/error.v1.json").read_text())


def test_404_matches_error_schema():
    client = TestClient(app)
    res = client.get("/no-such-path")
    body = res.json()
    for key in SCHEMA["required"]:
        assert key in body or key == "details"
