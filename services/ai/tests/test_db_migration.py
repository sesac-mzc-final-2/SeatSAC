"""6.4.1: 정적 계약 검사 + 선택 실행하는 격리 MySQL/Flyway 통합 검사.

AI_DB_MIGRATION_TEST=1일 때만 Docker를 사용한다. 기존 DB·.env·호스트 포트 사용 없음.
"""

import os
import re
import secrets
import subprocess
import time
from pathlib import Path
from uuid import uuid4

import pytest

from seatsac_ai.snapshot.schema import MOVIE_COLUMNS, SCREENING_COLUMNS, THEATER_COLUMNS

SERVICE = Path(__file__).resolve().parents[1]
MIGRATIONS = SERVICE / "db" / "migration"
TABLES = MIGRATIONS / "V202611260001__create_ai_tables.sql"
GRANTS = MIGRATIONS / "V202611260002__grant_ai_query_select.sql"


def table_columns(sql, table):
    body = re.search(rf"CREATE TABLE {table} \((.*?)\n\)", sql, re.S)[1]
    return {
        line.strip().split()[0]
        for line in body.splitlines()
        if line.strip() and not line.strip().startswith(("--", "INDEX", "CONSTRAINT"))
    }


def test_schema_preserves_public_columns_and_excludes_private_data():
    sql = TABLES.read_text()
    for table, columns in (
        ("movie", MOVIE_COLUMNS), ("theater", THEATER_COLUMNS), ("screening", SCREENING_COLUMNS)
    ):
        metadata = {"version", "deleted", "changed_at"} if table != "theater" else set()
        assert set(columns) | metadata == table_columns(sql, table)
    assert table_columns(sql, "unmet_demand") == {
        "id", "category", "reason", "question_hash", "slots", "created_at"
    }
    assert not re.search(r"\b(poster|user_id|question|FOREIGN KEY|ENUM)\b", sql, re.I)


def test_query_grants_are_exactly_three_selects():
    sql = re.sub(r"--[^\n]*", "", GRANTS.read_text())
    statements = [part.strip() for part in sql.split(";") if part.strip()]
    assert statements == [
        f"GRANT SELECT ON ai_db.{table} TO 'ai_query'@'%'"
        for table in ("movie", "theater", "screening")
    ]


def test_image_contains_migrations_without_running_them():
    dockerfile = (SERVICE / "Dockerfile").read_text()
    assert "COPY db/migration /app/db/migration" in dockerfile
    assert not re.search(r"^RUN .*flyway", dockerfile, re.M)


class LocalMysql:
    def __init__(self):
        self.name = f"seatsac-ai-migration-{uuid4().hex[:12]}"
        self.password = secrets.token_hex(24)

    def docker(self, *args, input=None, env=None):
        return subprocess.run(
            ["docker", *args], input=input, capture_output=True, text=True,
            env={**os.environ, **(env or {})}, timeout=120,
        )

    def sql(self, sql, user="ai_app", *, tls=True):
        return self.docker(
            "exec", "-i", "-e", "MYSQL_PWD", self.name,
            "mysql", "-h", "127.0.0.1", f"-u{user}", "--batch", "--skip-column-names",
            "--default-character-set=utf8mb4", f"--ssl-mode={'REQUIRED' if tls else 'DISABLED'}",
            "ai_db", input=sql, env={"MYSQL_PWD": self.password},
        )

    def ok(self, sql, user="ai_app"):
        result = self.sql(sql, user)
        # SQL·접속 정보·비밀번호를 실패 메시지에 넣지 않는다.
        assert result.returncode == 0, "격리 MySQL SQL 실행 실패"
        return result.stdout.strip()

    def migrate(self):
        return self.docker(
            "run", "--rm", "--pull=never", "--network", f"container:{self.name}",
            "-e", "FLYWAY_PASSWORD", "-v", f"{MIGRATIONS}:/flyway/sql:ro",
            "flyway/flyway:11.7.2",
            # 로컬 자체 서명 인증서만 신뢰. staging은 RDS CA·호스트 검증 필수.
            "-url=jdbc:mysql://127.0.0.1:3306/ai_db?useSsl=true&trustServerCertificate=true",
            "-user=ai_migrator", "-connectRetries=5", "migrate",
            env={"FLYWAY_PASSWORD": self.password},
        )


@pytest.fixture(scope="module")
def mysql_db():
    if os.environ.get("AI_DB_MIGRATION_TEST") != "1":
        pytest.skip("AI_DB_MIGRATION_TEST=1로 격리 Docker 통합 검사 실행")
    db = LocalMysql()
    result = db.docker(
        "run", "--rm", "--pull=never", "-d", "--network=none", "--name", db.name,
        "-e", "MYSQL_ROOT_PASSWORD", "-e", "MYSQL_DATABASE=ai_db", "mysql:8.4",
        "--default-time-zone=+00:00", "--character-set-server=utf8mb4",
        "--collation-server=utf8mb4_0900_ai_ci",
        env={"MYSQL_ROOT_PASSWORD": db.password},
    )
    assert result.returncode == 0, "격리 MySQL 시작 실패(Docker·로컬 이미지 확인)"
    try:
        for _ in range(60):
            if db.sql("SELECT 1", "root").returncode == 0:
                break
            time.sleep(1)
        else:
            pytest.fail("격리 MySQL 준비 시간 초과")
        # 인프라 db-bootstrap의 권한을 재현. migrator로만 마이그레이션한다.
        db.ok("\n".join([
            f"CREATE USER '{user}'@'%' IDENTIFIED BY '{db.password}' REQUIRE SSL;"
            for user in ("ai_app", "ai_migrator", "ai_query")
        ]) + "\n" + "\n".join([
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ai_db.* TO 'ai_app'@'%';",
            "GRANT ALL ON ai_db.* TO 'ai_migrator'@'%' WITH GRANT OPTION;",
            "CREATE DATABASE other_db; CREATE TABLE other_db.private_data (id INT);",
        ]), "root")
        result = db.migrate()
        assert result.returncode == 0, "Flyway 마이그레이션 실패(상세는 로컬에서 확인)"
        yield db
    finally:
        # 이 테스트가 만든 이름 한 개만 종료(--rm). 기존 컨테이너·파일은 건드리지 않는다.
        db.docker("stop", db.name)


def test_mysql_flyway_second_run_preserves_history_and_rows(mysql_db):
    db = mysql_db
    db.ok("INSERT INTO movie (movie_id, title) VALUES (1, '합성 테스트 영화')")
    assert db.migrate().returncode == 0
    assert db.ok("SELECT title FROM movie WHERE movie_id=1") == "합성 테스트 영화"
    assert db.ok(
        "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", "ai_migrator"
    ) == "2"


def test_mysql_schema_types_indexes_and_privacy(mysql_db):
    db = mysql_db
    assert db.ok("SHOW TABLES").splitlines() == [
        "flyway_schema_history", "movie", "processed_message", "screening", "theater",
        "unmet_demand",
    ]
    columns = db.ok("SHOW COLUMNS FROM unmet_demand")
    assert "created_at\tdatetime(3)" in columns
    assert "slots\tjson" in columns
    assert {line.split("\t")[0] for line in columns.splitlines()} == {
        "id", "category", "reason", "question_hash", "slots", "created_at"
    }
    indexes = db.ok("SHOW INDEX FROM unmet_demand")
    assert "idx_unmet_demand_created_at" in indexes
    assert "idx_unmet_demand_category" in indexes
    assert db.ok("SELECT @@session.time_zone") == "+00:00"


def test_mysql_accepts_catalog_maximum_lengths_and_delete_tombstones(mysql_db):
    db = mysql_db
    db.ok("""INSERT INTO screening (screening_id, film_format)
        VALUES (101, REPEAT('가', 40))""")
    assert db.ok("SELECT CHAR_LENGTH(film_format) FROM screening WHERE screening_id=101") == "40"
    db.ok("INSERT INTO movie (movie_id, version, deleted) VALUES (102, 3, TRUE)")
    db.ok("INSERT INTO screening (screening_id, version, deleted) VALUES (102, 3, TRUE)")
    assert db.ok("SELECT version FROM movie WHERE movie_id=102 AND deleted=TRUE") == "3"


@pytest.mark.parametrize("table", ["movie", "theater", "screening"])
def test_mysql_query_can_only_read_public_tables(mysql_db, table):
    assert mysql_db.sql(f"SELECT * FROM {table} LIMIT 1", "ai_query").returncode == 0


@pytest.mark.parametrize("sql", [
    "INSERT INTO movie (movie_id) VALUES (999)",
    "UPDATE movie SET title='변경' WHERE movie_id=1",
    "DELETE FROM movie WHERE movie_id=1",
    "CREATE TABLE forbidden (id INT)",
    "ALTER TABLE movie ADD COLUMN forbidden INT",
    "DROP TABLE movie",
    "SELECT * FROM unmet_demand",
    "SELECT * FROM processed_message",
    "SELECT * FROM flyway_schema_history",
    "SELECT * FROM other_db.private_data",
    "GRANT SELECT ON ai_db.movie TO 'ai_app'@'%'",
])
def test_mysql_query_denies_writes_private_tables_and_other_schema(mysql_db, sql):
    result = mysql_db.sql(sql, "ai_query")
    assert result.returncode != 0
    assert "denied" in result.stderr.lower()


def test_mysql_app_can_store_demand_but_cannot_create_tables(mysql_db):
    db = mysql_db
    db.ok("""INSERT INTO unmet_demand (category, reason, question_hash, slots, created_at)
        VALUES ('site_feature', NULL, REPEAT('a', 64), JSON_OBJECT(),
                '2026-11-25 15:00:00.123')""")
    assert db.ok("SELECT COUNT(*) FROM unmet_demand WHERE reason IS NULL") == "1"
    assert db.sql("CREATE TABLE app_forbidden (id INT)").returncode != 0
    assert db.sql("SELECT 1", "ai_query", tls=False).returncode != 0


def test_mysql_reason_groups_are_exact_and_slots_are_objects(mysql_db):
    db = mysql_db
    for reason in ("Cafe", "cafe", "Café", "Cafe "):
        db.ok(f"""INSERT INTO unmet_demand (category, reason, question_hash, slots, created_at)
            VALUES ('site_feature', '{reason}', 'test-hash', NULL,
                    '2026-11-25 15:00:00.123')""")
    assert db.ok("SELECT COUNT(DISTINCT reason) FROM unmet_demand") == "4"
    assert db.sql("""INSERT INTO unmet_demand (category, question_hash, slots, created_at)
        VALUES ('unknown', 'test-hash', JSON_ARRAY(), '2026-11-25 15:00:00.123')"""
    ).returncode != 0
