"""Prometheus 지표 (/actuator/prometheus). 이름·라벨 값은 service-runtime-contract 5장 ⚠️AI 행.

HTTP 지표는 Java 서비스와 같은 이름·라벨
(`http_server_requests_seconds_*{application,uri,status,method}`)이라
기존 기록 규칙·신호등을 그대로 쓴다. AI 지표는 `ai_*`.
라벨은 값 종류가 적은 것만 쓴다(사용자 ID·질문 문장 금지).

Counter는 prometheus_client가 `_total`을 붙이므로 이름에는 `_total`을 쓰지 않는다.
값 기록은 각 WBS 작업이 한다(3.4.3 경로·3.4.5 캐시·3.4.7 토큰·3.4.9 스냅샷). 여기서는
이름·라벨 값을 고정하고, 첫 건이 그래프에서 빠지지 않게 라벨 조합을 기동 때 0으로 등록한다.
"""

from __future__ import annotations

from prometheus_client import Counter, Gauge, Histogram

APPLICATION = "ai"

# --- 라벨 값 (계약 5장과 같은 문자열) -----------------------------------------
# 응답 필드 `path`는 대문자(CACHE_EXACT)이고, 지표 라벨은 소문자(cache_exact)다.
CHAT_PATHS = (
    "cache_exact",
    "cache_semantic",
    "preset",
    "nl2sql",
    "my_bookings",
    "rejected",
    "out_of_scope",
)
CHAT_RESULTS = ("ok", "error")
TOKEN_KINDS = ("input", "output", "cache_read", "cache_write")
CACHE_LAYERS = ("exact", "semantic")
CACHE_RESULTS = ("hit", "miss")
LLM_RESULTS = ("ok", "timeout", "overloaded", "rate_limited", "error")
SNAPSHOT_SCOPES = ("today", "other")
QUOTA_WINDOWS = ("minute", "day")

# --- 지표 ---------------------------------------------------------------------
HTTP_SERVER_REQUESTS = Histogram(
    "http_server_requests_seconds",
    "HTTP server request duration",
    ["application", "uri", "status", "method"],
)
AI_CHAT = Counter("ai_chat", "Chat requests by path and result", ["path", "result"])
AI_TOKENS = Counter("ai_tokens", "Claude API tokens", ["kind", "path"])
AI_CACHE_REQUESTS = Counter("ai_cache_requests", "Cache lookups", ["layer", "result"])
# Claude 호출 한도 15초(APP_AI_LLM_TIMEOUT_SECONDS)까지 p95가 보이게.
# 기본 버킷은 10초에서 잘린다(#95 리뷰)
LLM_CALL_BUCKETS = (0.5, 1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25)
AI_LLM_CALL_SECONDS = Histogram(
    "ai_llm_call_seconds", "Claude API call duration", ["result"], buckets=LLM_CALL_BUCKETS
)
AI_SQL_EXEC_SECONDS = Histogram("ai_sql_exec_seconds", "SQLite execution duration")
AI_REJECTED = Counter("ai_rejected", "SQL validator rejections", ["reason"])
AI_SNAPSHOT_AGE_SECONDS = Gauge("ai_snapshot_age_seconds", "Snapshot age", ["scope"])
AI_QUOTA_REJECTED = Counter("ai_quota_rejected", "Quota rejections", ["window"])


def chat_path_label(response_path: str) -> str:
    """응답 `path`(CACHE_EXACT) → 지표 라벨(cache_exact). 모르는 값은 실수이므로 예외."""
    label = response_path.lower()
    if label not in CHAT_PATHS:
        raise ValueError(f"unknown chat path: {response_path}")
    return label


def init_label_series() -> None:
    """라벨 조합을 0으로 미리 만든다(멱등). 첫 건이 증가분 계산에서 빠지는 것을 막는다."""
    for path in CHAT_PATHS:
        for result in CHAT_RESULTS:
            AI_CHAT.labels(path, result)
    for layer in CACHE_LAYERS:
        for result in CACHE_RESULTS:
            AI_CACHE_REQUESTS.labels(layer, result)
    for window in QUOTA_WINDOWS:
        AI_QUOTA_REJECTED.labels(window)


def record_http_request(uri: str, status: int, method: str, seconds: float) -> None:
    HTTP_SERVER_REQUESTS.labels(APPLICATION, uri, str(status), method).observe(seconds)
