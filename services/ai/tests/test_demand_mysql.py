"""PyMySQL → 실제 MySQL → HTTP 통계까지. opt-in, 합성 데이터·전용 임시 DB만 사용."""

import os
import ssl
import time
from datetime import datetime

import pymysql
import pytest
from fastapi.testclient import TestClient

from seatsac_ai.config import Settings
from seatsac_ai.demand.repository import MySqlDemandRepository
from seatsac_ai.demand.service import DemandService
from seatsac_ai.llm.orchestrator import ChatService
from seatsac_ai.main import create_app

from .llm_fakes import ScriptedLlm, tool
from .sql_fixtures import loaded_store
from .test_db_migration import LocalMysql


@pytest.fixture(scope="module")
def live_repository():
    if os.environ.get("AI_DB_MIGRATION_TEST") != "1":
        pytest.skip("AI_DB_MIGRATION_TEST=1로 격리 MySQL 드라이버 통합 검사 실행")
    db = LocalMysql()
    result = db.docker(
        "run", "--rm", "--pull=never", "-d", "--name", db.name,
        "--publish", "127.0.0.1::3306", "-e", "MYSQL_ROOT_PASSWORD",
        "-e", "MYSQL_DATABASE=ai_db", "mysql:8.4", "--default-time-zone=+00:00",
        "--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
        env={"MYSQL_ROOT_PASSWORD": db.password},
    )
    assert result.returncode == 0, "격리 드라이버 테스트 DB 시작 실패"
    try:
        for _ in range(60):
            if db.sql("SELECT 1", "root").returncode == 0:
                break
            time.sleep(1)
        else:
            pytest.fail("격리 드라이버 테스트 DB 준비 시간 초과")
        db.ok("\n".join([
            f"CREATE USER '{user}'@'%' IDENTIFIED BY '{db.password}' REQUIRE SSL;"
            for user in ("ai_app", "ai_migrator", "ai_query")
        ]) + "\n" + "\n".join([
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ai_db.* TO 'ai_app'@'%';",
            "GRANT ALL ON ai_db.* TO 'ai_migrator'@'%' WITH GRANT OPTION;",
        ]), "root")
        assert db.migrate().returncode == 0, "격리 DB Flyway 실패"
        port = int(db.docker("port", db.name, "3306/tcp").stdout.strip().rsplit(":", 1)[1])
        # 테스트 컨테이너의 일회성 자체서명 인증서만 예외. 앱 factory는 항상 CA/호스트 검증.
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        tls.check_hostname = False
        tls.verify_mode = ssl.CERT_NONE

        def connect():
            return pymysql.connect(
                host="127.0.0.1", port=port, user="ai_app", password=db.password,
                database="ai_db", charset="utf8mb4", ssl=tls,
                connect_timeout=1, read_timeout=1, write_timeout=1,
                init_command="SET SESSION time_zone = '+00:00', MAX_EXECUTION_TIME = 1500",
            )

        yield MySqlDemandRepository(connect)
    finally:
        db.docker("stop", db.name)


@pytest.fixture
def repository(live_repository):
    with live_repository._connect() as conn:
        with conn.cursor() as cursor:
            cursor.execute("DELETE FROM unmet_demand")
        conn.commit()
    return live_repository


def seed(repo, category, reason, timestamp):
    repo.insert(category=category, reason=reason, question_hash="a" * 16, slots={},
                created_at=datetime.fromisoformat(timestamp))


def test_mysql_kst_boundaries_exact_groups_limits_and_http_contract(repository):
    seed(repository, "unknown", "기간 밖", "2026-11-23 14:59:59.999")
    seed(repository, "unknown", "기간 밖", "2026-11-24 15:00:00.000")
    seed(repository, "site_feature", "Cafe", "2026-11-23 15:00:00.000")
    for reason in ("Cafe", "cafe", "Café", "Cafe "):
        seed(repository, "site_feature", reason, "2026-11-24 00:00:00.000")
    for _ in range(2):
        seed(repository, "action_request", "취소 문의", "2026-11-24 00:00:00.000")
        seed(repository, "recommendation", None, "2026-11-24 14:59:59.999")
    settings = Settings(_env_file=None)
    app = create_app(settings)
    app.state.demand = DemandService(repository)
    with TestClient(app) as client:
        response = client.get("/internal/ai/unmet-demand/stats", params={
            "from": "2026-11-24", "to": "2026-11-24", "limit": 100,
        })
        assert response.status_code == 200
        body = response.json()
        assert set(body) == {"from", "to", "total", "byCategory", "top"}
        assert body["total"] == 9
        assert body["byCategory"] == [
            {"category": "site_feature", "count": 5},
            {"category": "action_request", "count": 2},
            {"category": "recommendation", "count": 2},
        ]
        assert body["top"] == [
            {"category": "action_request", "reason": "취소 문의", "count": 2},
            {"category": "site_feature", "reason": "Cafe", "count": 2},
            {"category": "site_feature", "reason": "Cafe ", "count": 1},
            {"category": "site_feature", "reason": "Café", "count": 1},
            {"category": "site_feature", "reason": "cafe", "count": 1},
        ]
        assert sum(row["count"] for row in body["top"]) == 7
        limited = client.get("/internal/ai/unmet-demand/stats", params={
            "from": "2026-11-24", "to": "2026-11-24", "limit": 1,
        }).json()
        assert limited["total"] == 9 and len(limited["top"]) == 1
        next_day = client.get("/internal/ai/unmet-demand/stats", params={
            "from": "2026-11-25", "to": "2026-11-25",
        }).json()
        assert next_day["total"] == 1  # 24일 UTC 15시는 KST 25일 자정에 포함된다.
        empty = client.get("/internal/ai/unmet-demand/stats", params={
            "from": "2026-11-26", "to": "2026-11-26",
        }).json()
        assert empty["total"] == 0 and empty["byCategory"] == empty["top"] == []


def test_chat_to_real_db_to_stats_keeps_snapshot_search(repository):
    settings = Settings(_env_file=None)
    app = create_app(settings)
    demand = DemandService(repository)
    app.state.demand = demand
    llm = ScriptedLlm(tool(
        "out_of_scope", category="site_feature", reason="연락처 010-0000-0000 문의",
    ))
    app.state.chat = ChatService(loaded_store(), llm, settings, demand=demand)
    question = "합성 개인 메일 demo@example.invalid 로 요청합니다"
    with TestClient(app) as client:
        response = client.post("/ai/chat", json={"message": question}, headers={
            "X-User-Id": "11111111-2222-3333-4444-555555555555",
        })
        assert response.status_code == 200 and response.json()["path"] == "OUT_OF_SCOPE"
        body = client.get("/internal/ai/unmet-demand/stats").json()
        assert body["total"] == 1
        assert body["top"] == [
            {"category": "site_feature", "reason": "연락처 [숫자] 문의", "count": 1}
        ]
        with repository._connect() as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT question_hash, slots FROM unmet_demand")
                hashed, slots = cursor.fetchone()
        assert len(hashed) == 16 and slots == "{}"
        assert question not in str(body) and "demo@" not in str(body)
        assert app.state.chat._store.is_ready()  # 영화 검색은 계속 SQLite 스냅샷이다.


def test_mysql_statistics_use_one_snapshot_during_concurrent_insert(repository):
    seed(repository, "site_feature", "먼저 저장된 수요", "2026-11-24 00:00:00")
    inserted = False

    class RaceCursor(pymysql.cursors.Cursor):
        def fetchone(self):
            nonlocal inserted
            result = super().fetchone()
            if not inserted:
                inserted = True
                # total 조회와 다음 집계 사이에 다른 연결에서 새 수요가 커밋된다.
                seed(repository, "recommendation", "동시에 들어온 수요", "2026-11-24 01:00:00")
            return result

    def connect():
        conn = repository._connect()
        conn.cursorclass = RaceCursor
        return conn

    raced = MySqlDemandRepository(connect)
    start, end = datetime(2026, 11, 23, 15), datetime(2026, 11, 24, 15)
    body = raced.stats(start, end, 20)
    assert inserted and body["total"] == 1
    assert body["byCategory"] == [{"category": "site_feature", "count": 1}]
    assert body["top"] == [
        {"category": "site_feature", "reason": "먼저 저장된 수요", "count": 1}
    ]
    assert repository.stats(start, end, 20)["total"] == 2  # 다음 조회부터 새 수요 반영
