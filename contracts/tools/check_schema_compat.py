#!/usr/bin/env python3
"""
이벤트·공용 JSON Schema 하위 호환 검사 (2.2.7 계약 CI)

사용: python3 contracts/tools/check_schema_compat.py <base contracts 폴더> <head contracts 폴더>
      예) python3 contracts/tools/check_schema_compat.py base/contracts contracts

대상: events/*.json, schemas/*.json (examples/ 하위는 제외)
규칙 (contracts/README.md "호환 규칙"):
  ERROR  기존 파일 삭제 · 속성 삭제 · 타입 변경/축소 · 필수 필드 추가 · enum 값 삭제 · const 변경
         · additionalProperties false로 변경 · 최소/최대 제약 강화 · pattern/format 변경
  WARN   enum 값 추가(소비자가 모르는 값을 받게 됨) · 필수 필드를 선택으로 완화
  OK     선택 속성 추가 · 설명(description) 변경 · 새 스키마 파일(새 버전 v2 포함)
종료 코드: ERROR가 하나라도 있으면 1 (--warn-only면 0)
GitHub Actions에서는 ::error / ::warning 주석으로 PR 화면에 표시된다.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

GITHUB = os.environ.get("GITHUB_ACTIONS") == "true"


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, file: str, where: str, msg: str) -> None:
        self.errors.append(f"{file} {where}: {msg}")
        if GITHUB:
            print(f"::error file=contracts/{file}::{where}: {msg}")

    def warn(self, file: str, where: str, msg: str) -> None:
        self.warnings.append(f"{file} {where}: {msg}")
        if GITHUB:
            print(f"::warning file=contracts/{file}::{where}: {msg}")


def types_of(schema: dict) -> set[str] | None:
    t = schema.get("type")
    if t is None:
        return None
    return {t} if isinstance(t, str) else set(t)


def compare(file: str, path: str, old: dict, new: dict, rep: Report) -> None:
    where = path or "/"

    # 타입: 새 타입 집합이 기존을 모두 포함해야 한다 (넓히기만 허용)
    ot, nt = types_of(old), types_of(new)
    if ot is not None:
        if nt is None:
            pass  # 타입 제약을 없앤 것 = 넓힘
        elif not ot <= nt:
            rep.error(file, where, f"타입 변경 {sorted(ot)} → {sorted(nt)}")

    # const
    if "const" in old and old.get("const") != new.get("const"):
        rep.error(file, where, f"const 변경 {old.get('const')!r} → {new.get('const')!r}")

    # enum
    if "enum" in old:
        oe = old["enum"]
        ne = new.get("enum")
        if ne is not None:
            removed = [v for v in oe if v not in ne]
            added = [v for v in ne if v not in oe]
            if removed:
                rep.error(file, where, f"enum 값 삭제 {removed}")
            if added:
                rep.warn(file, where, f"enum 값 추가 {added} (소비자가 모르는 값을 무시하는지 확인)")
    elif "enum" in new:
        rep.error(file, where, f"enum 제약 새로 추가 {new['enum']}")

    # 형식·패턴
    for key in ("format", "pattern"):
        if old.get(key) != new.get(key) and new.get(key) is not None:
            rep.error(file, where, f"{key} 변경 {old.get(key)!r} → {new.get(key)!r}")

    # 숫자·길이 제약: 강화만 막는다
    for key, stricter in (("minimum", lambda o, n: n > o), ("minLength", lambda o, n: n > o),
                          ("minItems", lambda o, n: n > o), ("maximum", lambda o, n: n < o),
                          ("maxLength", lambda o, n: n < o), ("maxItems", lambda o, n: n < o)):
        if key in new:
            if key not in old:
                rep.error(file, where, f"{key} 제약 새로 추가 ({new[key]})")
            elif stricter(old[key], new[key]):
                rep.error(file, where, f"{key} 강화 {old[key]} → {new[key]}")

    # additionalProperties
    if old.get("additionalProperties", True) is not False and new.get("additionalProperties", True) is False:
        rep.error(file, where, "additionalProperties: false 추가 (모르는 필드 무시 원칙 위반)")

    # required
    oreq, nreq = set(old.get("required", [])), set(new.get("required", []))
    for f in sorted(nreq - oreq):
        rep.error(file, where, f"필수 필드 추가 '{f}' (기존 발행자 메시지가 실패함)")
    for f in sorted(oreq - nreq):
        rep.warn(file, where, f"필수 필드 '{f}'를 선택으로 완화 (소비자가 없을 때를 처리하는지 확인)")

    # properties
    oprops, nprops = old.get("properties", {}), new.get("properties", {})
    for name, osub in oprops.items():
        sub_path = f"{path}/properties/{name}"
        if name not in nprops:
            rep.error(file, sub_path, "속성 삭제")
            continue
        if isinstance(osub, dict) and isinstance(nprops[name], dict):
            compare(file, sub_path, osub, nprops[name], rep)

    # items
    if isinstance(old.get("items"), dict):
        if isinstance(new.get("items"), dict):
            compare(file, f"{path}/items", old["items"], new["items"], rep)
        elif "items" not in new:
            pass  # 항목 제약을 없앤 것 = 넓힘
        else:
            rep.error(file, f"{path}/items", "items 형식 변경")


def schema_files(root: Path) -> dict[str, Path]:
    out: dict[str, Path] = {}
    for sub in ("events", "schemas"):
        d = root / sub
        if d.is_dir():
            for p in sorted(d.glob("*.json")):
                out[f"{sub}/{p.name}"] = p
    return out


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    warn_only = "--warn-only" in sys.argv
    if len(args) != 2:
        print(__doc__)
        return 2
    base, head = Path(args[0]), Path(args[1])
    rep = Report()

    base_files, head_files = schema_files(base), schema_files(head)
    if not base_files:
        print(f"base에 스키마가 없습니다({base}). 첫 도입이면 정상입니다.")

    for rel, bpath in base_files.items():
        if rel not in head_files:
            rep.error(rel, "/", "기존 스키마 파일 삭제 (운영 중 메시지를 검증할 수 없게 됨)")
            continue
        old = json.loads(bpath.read_text(encoding="utf-8"))
        new = json.loads(head_files[rel].read_text(encoding="utf-8"))
        compare(rel, "", old, new, rep)

    for rel in sorted(set(head_files) - set(base_files)):
        print(f"새 스키마: {rel}")

    print()
    for w in rep.warnings:
        print(f"WARN  {w}")
    for e in rep.errors:
        print(f"ERROR {e}")
    print(f"\n결과: ERROR {len(rep.errors)} · WARN {len(rep.warnings)} · 검사 {len(base_files)}개 파일")

    if rep.errors and not warn_only:
        print("하위 호환이 깨집니다. 새 버전 파일(예: seat-event.v2.json)로 만들거나, "
              "설계 단계라면 PR에 'contract-break-ok' 라벨 + CODEOWNERS 승인을 받으세요.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
