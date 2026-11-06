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

    # 캐시·세션·한도
    cache_ttl: Seconds = Field(3600, alias="APP_AI_CACHE_TTL")
    session_ttl: Seconds = Field(1800, alias="APP_AI_SESSION_TTL")
    semantic_cache_enabled: bool = Field(True, alias="APP_AI_SEMANTIC_CACHE_ENABLED")
    semantic_threshold: float = Field(0.92, alias="APP_AI_SEMANTIC_THRESHOLD")
    limit_per_minute: int = Field(10, alias="APP_AI_LIMIT_PER_MINUTE")
    limit_per_day: int = Field(100, alias="APP_AI_LIMIT_PER_DAY")


@lru_cache
def get_settings() -> Settings:
    return Settings()
