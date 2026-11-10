"""Claude에게 보낼 안내문(system)과 질문(user) 만들기.

system 은 요청마다 **글자 하나 다르지 않게 같다** — 설명서(schema.yaml) + 프리셋 목록.
그래서 프롬프트 캐싱이 적중해 이 긴 부분을 싸게 읽는다(지표 kind=cache_read).
매번 바뀌는 값(오늘 날짜·지금 시각)은 system 이 아니라 user 메시지 앞에 붙인다.
"""

from __future__ import annotations

from datetime import datetime
from functools import lru_cache
from typing import Any

import yaml

from seatsac_ai.resources import presets, schema_doc

_WEEKDAYS = "월화수목금토일"

_ROLE = """너는 영화 예매 사이트 SeatSAC의 상영 정보 도우미다.
사용자의 질문을 보고 도구 하나를 반드시 호출한다. 글로 답하지 않는다.
1. 질문이 프리셋 하나와 뜻이 같으면 run_preset (SQL을 쓰지 않는다)
2. 프리셋은 없지만 아래 표로 답할 수 있으면 run_sql
3. 영화·극장·회차와 상관없거나 표에 없는 정보(가격·리뷰·예매 대행 등)면 out_of_scope
"내 예매" 같은 개인 정보는 표에 없다(서버가 따로 처리한다)."""


@lru_cache
def system_text() -> str:
    doc = schema_doc()
    preset_lines = "\n".join(
        f"- {p.preset_id}: {p.description} (빈칸: {', '.join(p.params) or '없음'}) 예: {p.example}"
        for p in presets()
    )
    schema_part = yaml.safe_dump(
        {k: doc[k] for k in ("rules", "tables", "joins", "enums", "examples")},
        allow_unicode=True,
        sort_keys=False,
        width=200,
    )
    return (
        f"{_ROLE}\n\n## 프리셋\n{preset_lines}\n\n"
        f"## 스냅샷 표 설명 (SQLite)\n{schema_part}"
        "예시의 날짜는 '오늘 = 2026-11-17(화), 지금 = 19:40'을 가정한 값이다. "
        "실제 오늘·지금은 질문 위에 주어진다."
    )


def system_blocks(cache: bool) -> list[dict[str, Any]]:
    block: dict[str, Any] = {"type": "text", "text": system_text()}
    if cache:
        # 도구 정의 + system 까지가 캐시 앞부분이 된다
        block["cache_control"] = {"type": "ephemeral"}
    return [block]


def user_text(question: str, now: datetime) -> str:
    return (
        f"오늘: {now:%Y-%m-%d}({_WEEKDAYS[now.weekday()]}) · 지금: {now:%Y-%m-%d %H:%M} (KST)\n"
        f"질문: {question}"
    )
