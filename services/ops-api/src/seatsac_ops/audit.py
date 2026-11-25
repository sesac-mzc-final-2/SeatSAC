"""감사 로그 — 모든 쓰기 조작(정지·가동·쿠폰 이벤트 생성)과 리포트 쿼리 시작에 한 줄(ADR-119,
ops.openapi.yaml).

`ops audit` 메시지 + userId·action·target·result. 토큰·presigned URL·요청 본문은 남기지 않는다.
"""

from __future__ import annotations

import logging

log = logging.getLogger("seatsac_ops.audit")


def audit(user_id: str, action: str, target: str | None, result: str, **extra: object) -> None:
    fields: dict[str, object] = {
        "audit": True,
        "userId": user_id,
        "action": action,
        "target": target,
        "result": result,
    }
    fields.update({k: v for k, v in extra.items() if v is not None})
    log.info("ops audit", extra={"fields": fields})
