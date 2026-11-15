"""Claude가 고를 수 있는 도구 3개 (기능명세서 v4 15-2 6단계).

Claude는 답을 글로 쓰지 않고 반드시 이 중 하나를 "호출"한다(tool_choice=any).
  run_preset   미리 써 둔 SQL(presets.yaml) + 빈칸 값   → path PRESET
  run_sql      Claude가 직접 쓴 SQL                     → path NL2SQL (검증기 통과 시)
  out_of_scope 영화·극장·회차와 상관없는 질문            → path OUT_OF_SCOPE
"""

from __future__ import annotations

from typing import Any

from seatsac_ai.resources import presets

RUN_PRESET = "run_preset"
RUN_SQL = "run_sql"
OUT_OF_SCOPE = "out_of_scope"

_SLOT_SCHEMA = {
    "time": {"type": "string", "pattern": "^[0-2][0-9]:[0-5][0-9]$", "description": "HH:MM"},
    "date": {"type": "string", "pattern": r"^\d{4}-\d{2}-\d{2}$", "description": "YYYY-MM-DD"},
    "theater": {"type": "string", "description": "극장 이름(스냅샷 theater.name 그대로)"},
    "movie": {"type": "string", "description": "영화 제목(스냅샷 movie.title 그대로)"},
    "n": {"type": "integer", "minimum": 1, "maximum": 50, "description": "개수"},
    "grade": {
        "type": "string",
        "enum": ["ALL", "12", "15", "19"],
        "description": "관람 등급. 전체 관람가=ALL, 12세=12, 15세=15, 청소년 관람불가=19",
    },
}


def tool_definitions() -> list[dict[str, Any]]:
    return [
        {
            "name": RUN_PRESET,
            "description": (
                "질문이 아래 프리셋 중 하나와 뜻이 같으면 이 도구를 쓴다. "
                "SQL을 쓰지 말고 presetId와 빈칸 값(params)만 채운다. 프리셋 목록은 system에 있다."
            ),
            "input_schema": {
                "type": "object",
                "properties": {
                    "presetId": {"type": "string", "enum": [p.preset_id for p in presets()]},
                    "params": {
                        "type": "object",
                        "properties": _SLOT_SCHEMA,
                        "additionalProperties": False,
                    },
                },
                "required": ["presetId", "params"],
            },
        },
        {
            "name": RUN_SQL,
            "description": (
                "맞는 프리셋이 없지만 스냅샷 표로 답할 수 있으면 SQLite SELECT 한 문장을 쓴다. "
                "explanation에는 결과가 무엇인지 한 구절로 적는다(예: '오늘 20시 이후 회차')."
            ),
            "input_schema": {
                "type": "object",
                "properties": {
                    "sql": {"type": "string"},
                    "explanation": {"type": "string", "maxLength": 80},
                },
                "required": ["sql", "explanation"],
            },
        },
        {
            "name": OUT_OF_SCOPE,
            "description": "영화·극장·상영 회차 정보로 답할 수 없는 질문이면 이 도구를 쓴다.",
            "input_schema": {
                "type": "object",
                "properties": {"reason": {"type": "string", "maxLength": 120}},
                "required": ["reason"],
            },
        },
    ]
