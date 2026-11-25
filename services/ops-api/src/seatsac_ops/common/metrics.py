"""Prometheus 지표 (/actuator/prometheus). 이름·라벨은 service-runtime-contract 5장.

HTTP 지표는 Java 서비스와 같은 이름(`http_server_requests_seconds_*{application,uri,status,method}`)
—
기존 기록 규칙·신호등을 그대로 쓴다. 관리자 조작은 `ops_action_total{action, result}`.
"""

from __future__ import annotations

from prometheus_client import Counter, Histogram

APPLICATION = "ops-api"

ACTIONS = ("stop", "start", "report")
ACTION_RESULTS = ("ok", "denied", "error")

HTTP_SERVER_REQUESTS = Histogram(
    "http_server_requests_seconds",
    "HTTP server request duration",
    ["application", "uri", "status", "method"],
)
# Counter는 prometheus_client가 _total을 붙인다 → ops_action_total
OPS_ACTION = Counter("ops_action", "Admin operations by action and result", ["action", "result"])


def init_label_series() -> None:
    """라벨 조합을 0으로 미리 만든다(첫 건이 증가분 계산에서 빠지지 않게, 멱등)."""
    for action in ACTIONS:
        for result in ACTION_RESULTS:
            OPS_ACTION.labels(action, result)


def record_http_request(uri: str, status: int, method: str, seconds: float) -> None:
    HTTP_SERVER_REQUESTS.labels(APPLICATION, uri, str(status), method).observe(seconds)


def record_action(action: str, result: str) -> None:
    if action in ACTIONS and result in ACTION_RESULTS:
        OPS_ACTION.labels(action, result).inc()
