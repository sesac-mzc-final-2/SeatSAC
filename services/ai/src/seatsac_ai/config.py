"""설정. 이름·기본값은 docs/service-runtime-contract.md 9-1과 같다.

Python은 Spring의 `${}` 치환이 없어서 환경변수 이름을 그대로 읽는다.
시간 값은 `60s`·`10m`·`1h`·`1d` 형태 또는 초 단위 숫자를 받아 초(int)로 바꾼다.
"""

from __future__ import annotations

import re
from functools import lru_cache
from typing import Annotated, Literal

from pydantic import BeforeValidator, Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

_UNIT_SECONDS = {"s": 1, "m": 60, "h": 3600, "d": 86400}


def _to_seconds(value: object) -> int:
    if isinstance(value, bool):
        raise ValueError("duration must not be a bool")
    if isinstance(value, int | float):
        return int(value)
    match = re.fullmatch(r"\s*(\d+)\s*([smhd])?\s*", str(value))
    if match is None:
        raise ValueError(f"invalid duration: {value!r} (예: 60s, 10m, 1h)")
    return int(match.group(1)) * _UNIT_SECONDS[match.group(2) or "s"]


Seconds = Annotated[int, BeforeValidator(_to_seconds)]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",  # 로컬 전용(커밋 금지). staging은 ConfigMap·Secret 환경변수
        extra="ignore",
        populate_by_name=True,
    )

    # 프로필: local(사람이 읽는 로그) | staging(JSON 로그). 이미지 기본값은 staging(Dockerfile)
    app_env: Literal["local", "staging"] = Field("local", alias="APP_ENV")

    # 인프라 엔드포인트 (Terraform cinema-infra-endpoints)
    ai_valkey_host: str | None = Field(None, alias="AI_VALKEY_HOST")
    ai_valkey_port: int = Field(6379, alias="APP_AI_VALKEY_PORT")
    ai_valkey_tls: bool = Field(True, alias="APP_AI_VALKEY_TLS")
    public_api_base_url: str | None = Field(None, alias="PUBLIC_API_BASE_URL")

    # 수요 저장·통계는 영화 검색 데이터 소스와 분리(6.4.3·6.4.4).
    unmet_demand_enabled: bool = Field(False, alias="APP_AI_UNMET_DEMAND_ENABLED")
    db_host: str | None = Field(None, alias="DB_HOST_AI")
    db_port: int = Field(3306, ge=1, le=65535, alias="APP_AI_DB_PORT")
    db_password: SecretStr | None = Field(None, alias="APP_AI_DB_PASSWORD")
    db_ssl_ca: str | None = Field(None, alias="APP_AI_DB_SSL_CA")

    # LLM (ADR-83)
    ai_model: str = Field("claude-sonnet-5-5", alias="APP_AI_MODEL")
    # 요청 하나의 전체 기한(Claude 호출·SQL 실행·재요청 전부).
    # 시간 예산(9-1): CloudFront 30 > gateway 28 > 이 값 25
    request_deadline_seconds: int = Field(25, alias="APP_AI_REQUEST_DEADLINE_SECONDS")
    # Claude 호출 1회 상한. 실제로는 min(이 값, 전체 기한까지 남은 시간)
    llm_timeout_seconds: int = Field(15, alias="APP_AI_LLM_TIMEOUT_SECONDS")
    llm_max_calls_per_query: int = Field(3, alias="APP_AI_LLM_MAX_CALLS_PER_QUERY")
    prompt_cache_enabled: bool = Field(True, alias="APP_AI_PROMPT_CACHE_ENABLED")
    anthropic_api_key: SecretStr | None = Field(None, alias="ANTHROPIC_API_KEY")

    # SQL 실행
    sql_timeout_seconds: int = Field(5, alias="APP_AI_SQL_TIMEOUT_SECONDS")
    max_rows: int = Field(500, alias="APP_AI_MAX_ROWS")

    # 스냅샷
    snapshot_today_interval: Seconds = Field(60, alias="APP_AI_SNAPSHOT_TODAY_INTERVAL")
    snapshot_other_interval: Seconds = Field(600, alias="APP_AI_SNAPSHOT_OTHER_INTERVAL")
    snapshot_days: int = Field(7, alias="APP_AI_SNAPSHOT_DAYS")
    # 잔여석을 읽는 SQL 직전에 그 SQL이 조회하는 날짜 × 극장 회차를 새로 받는다(질문 시점 갱신).
    # 제한 시간 안에 못 받으면 기존 스냅샷으로 답한다
    snapshot_refresh_on_chat: bool = Field(True, alias="APP_AI_SNAPSHOT_REFRESH_ON_CHAT")
    snapshot_refresh_timeout_seconds: float = Field(
        2.0, alias="APP_AI_SNAPSHOT_REFRESH_TIMEOUT_SECONDS"
    )

    # 채팅으로 좌석 잡기(3.4.19, seat_hold.py). 기본 켜짐 — 문제가 생기면 false 로 끈다.
    # 꺼져 있으면 도구 목록·system·응답이 좌석 잡기가 없던 때와 같다.
    # 켜져 있어도 ai Valkey나 공개 API 주소가 없으면 꺼진 것으로 동작한다(orchestrator)
    seat_hold_enabled: bool = Field(True, alias="APP_AI_SEAT_HOLD_ENABLED")
    # 확인 버튼을 기다리는 시간. 지나면 대기 행동이 사라져 확인을 눌러도 잡지 않는다
    seat_hold_confirm_ttl: Seconds = Field(120, alias="APP_AI_SEAT_HOLD_CONFIRM_TTL")

    # 캐시·세션·한도
    cache_ttl: Seconds = Field(3600, alias="APP_AI_CACHE_TTL")
    session_ttl: Seconds = Field(1800, alias="APP_AI_SESSION_TTL")
    semantic_cache_enabled: bool = Field(True, alias="APP_AI_SEMANTIC_CACHE_ENABLED")
    semantic_threshold: float = Field(0.92, alias="APP_AI_SEMANTIC_THRESHOLD")
    # 버튼 질문(프리셋) 말투를 기동 때 캐시에 미리 넣는다 — 첫 사용자도 캐시로 답한다(cache/warm.py)
    cache_warm_presets: bool = Field(True, alias="APP_AI_CACHE_WARM_PRESETS")
    # Claude가 버튼을 골라 답한 새 말투를 장부에 적고 예열 루프가 계속 다시 넣는다(cache/learned.py)
    cache_learn_enabled: bool = Field(True, alias="APP_AI_CACHE_LEARN_ENABLED")
    limit_per_minute: int = Field(10, alias="APP_AI_LIMIT_PER_MINUTE")
    limit_per_day: int = Field(100, alias="APP_AI_LIMIT_PER_DAY")


@lru_cache
def get_settings() -> Settings:
    return Settings()
