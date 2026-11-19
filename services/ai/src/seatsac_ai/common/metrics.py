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
# 채팅으로 좌석 잡기(플래그 APP_AI_SEAT_HOLD_ENABLED, 기본 켜짐). 켜졌을 때만 0으로 등록한다.
# 조회 답이 아니므로 토큰 절감 계산의 분모·분자에서 뺀다(계약 5장)
HOLD_CHAT_PATHS = ("hold_confirm", "hold_result", "hold_guide")
CHAT_RESULTS = ("ok", "error")
TOKEN_KINDS = ("input", "output", "cache_read", "cache_write")
CACHE_LAYERS = ("exact", "semantic")
CACHE_RESULTS = ("hit", "miss")
# 스스로 배우기(cache/learned.py) 결과 — learned.LEARN_RESULTS 와 같은 값(테스트가 지킨다)
LEARN_RESULTS = ("stored", "known", "dropped", "skipped", "full", "error")
LLM_RESULTS = ("ok", "timeout", "overloaded", "rate_limited", "error")
SNAPSHOT_SCOPES = ("today", "other")
QUOTA_WINDOWS = ("minute", "day")
# 범위 밖 질문 중 "수요"로 세는 분류 + out_of_scope 도구의 category 가 정해진 값이 아닌 경우
# recommendation = 조건 없는 영화 추천 요청(3.4.25) — 추천 기능을 만들 근거로 따로 센다
UNMET_CATEGORIES = ("action_request", "site_feature", "recommendation", "unrelated")
UNMET_UNKNOWN = "unknown"
# 욕설·인사·잡담은 수요가 아니라 세지 않는다. Claude가 고를 수는 있어야 다른 분류로 새지 않는다
UNMET_CHITCHAT = "chitchat"
OUT_OF_SCOPE_CATEGORIES = (*UNMET_CATEGORIES, UNMET_CHITCHAT)  # out_of_scope 도구의 category enum

# --- 지표 ---------------------------------------------------------------------
HTTP_SERVER_REQUESTS = Histogram(
    "http_server_requests_seconds",
    "HTTP server request duration",
    ["application", "uri", "status", "method"],
)
AI_CHAT = Counter("ai_chat", "Chat requests by path and result", ["path", "result"])
AI_TOKENS = Counter("ai_tokens", "Claude API tokens", ["kind", "path"])
AI_CACHE_REQUESTS = Counter("ai_cache_requests", "Cache lookups", ["layer", "result"])
# Claude가 버튼을 골라 답한 새 말투를 장부에 적은 결과 — stored 가 "새로 배운 말투" 수
AI_CACHE_LEARNED = Counter("ai_cache_learned", "Learned phrasings by result", ["result"])
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
# 사용자가 원했지만 답하지 못한 요청(OUT_OF_SCOPE)을 분류별로 센다 — 다음 개발 후보를 고르는 근거
AI_UNMET_DEMAND = Counter("ai_unmet_demand", "Out-of-scope requests by category", ["category"])


def chat_path_label(response_path: str) -> str:
    """응답 `path`(CACHE_EXACT) → 지표 라벨(cache_exact). 모르는 값은 실수이므로 예외."""
    label = response_path.lower()
    if label not in CHAT_PATHS and label not in HOLD_CHAT_PATHS:
        raise ValueError(f"unknown chat path: {response_path}")
    return label


def unmet_category_label(value: object) -> str:
    """Claude가 적은 category → 지표 라벨. 정해 둔 값이 아니면 unknown.

    임의 글자가 라벨로 들어가 시계열이 늘어나는 것을 막는다.
    chitchat 은 부르는 쪽이 먼저 걸러 여기로 오지 않는다(세지 않는 분류).
    """
    return value if isinstance(value, str) and value in UNMET_CATEGORIES else UNMET_UNKNOWN


def init_label_series(seat_hold: bool = False) -> None:
    """라벨 조합을 0으로 미리 만든다(멱등). 첫 건이 증가분 계산에서 빠지는 것을 막는다.

    seat_hold = 좌석 잡기 플래그. 꺼져 있으면 hold_* 라벨을 만들지 않는다.
    """
    for path in CHAT_PATHS + (HOLD_CHAT_PATHS if seat_hold else ()):
        for result in CHAT_RESULTS:
            AI_CHAT.labels(path, result)
    for layer in CACHE_LAYERS:
        for result in CACHE_RESULTS:
            AI_CACHE_REQUESTS.labels(layer, result)
    for result in LEARN_RESULTS:
        AI_CACHE_LEARNED.labels(result)
    for window in QUOTA_WINDOWS:
        AI_QUOTA_REJECTED.labels(window)
    for category in (*UNMET_CATEGORIES, UNMET_UNKNOWN):
        AI_UNMET_DEMAND.labels(category)


def record_http_request(uri: str, status: int, method: str, seconds: float) -> None:
    HTTP_SERVER_REQUESTS.labels(APPLICATION, uri, str(status), method).observe(seconds)
