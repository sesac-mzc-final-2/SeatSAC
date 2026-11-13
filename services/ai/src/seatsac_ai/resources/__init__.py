"""Claude에게 주는 설명서·프리셋(코드가 아닌 데이터). 이미지에 함께 들어간다.

- schema.yaml: 스냅샷 표·칸 설명, 값 뜻, 시간 규칙, 예시 SQL (3.4.1)
- presets.yaml: 추천 질문 12개와 바인딩 SQL (3.4.4)
"""

from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from importlib.resources import files
from typing import Any

import yaml

# 슬롯 종류(질문에서 뽑아 바꾸는 값). 기능명세서 v4 15-2 3단계와 같은 이름
SLOT_KINDS = ("time", "date", "theater", "movie", "n", "grade")
# 서버가 항상 넣는 값. 프리셋 `params`에는 적지 않는다
SYSTEM_PARAMS = ("today", "now")


def _load(name: str) -> Any:
    return yaml.safe_load(files(__package__).joinpath(name).read_text(encoding="utf-8"))


@lru_cache
def schema_doc() -> dict[str, Any]:
    return _load("schema.yaml")


@dataclass(frozen=True)
class Preset:
    preset_id: str
    title: str
    example: str
    description: str
    params: tuple[str, ...]
    sql: str


MOVIE_PLACEHOLDER = "영화 제목"  # 계약 ai.openapi.yaml GET /ai/presets 예시와 같은 자리 표시


def fill_example(example: str, movie: str | None) -> str:
    """버튼 예시의 {movie} 를 실제 상영 중인 영화 제목으로(없으면 '영화 제목')."""
    return example.replace("{movie}", movie or MOVIE_PLACEHOLDER)


@lru_cache
def presets() -> tuple[Preset, ...]:
    raw = _load("presets.yaml")["presets"]
    return tuple(
        Preset(
            preset_id=p["presetId"],
            title=p["title"],
            example=p["example"],
            description=p["description"],
            params=tuple(p.get("params") or ()),
            sql=p["sql"].strip(),
        )
        for p in raw
    )
