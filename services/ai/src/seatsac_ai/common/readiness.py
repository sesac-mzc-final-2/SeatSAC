"""readiness 점검 등록소.

최종 readiness 조건은 「첫 스냅샷 적재 완료 + ai Valkey PING」(service-runtime-contract 9장)이다.
둘 다 이 골격(3.4.8)에는 아직 없으므로 점검을 등록하지 않는다. 각 작업이 자기 점검을 등록한다.
  - 3.4.9 스냅샷 적재기: register("snapshot", ...)
  - 3.4.5 의미 캐시(ai Valkey): register("valkey", ...)
점검이 없으면 UP이라서, 골격만 배포해도 파드가 Ready가 된다(rc1 「플래그 off로 staging 배포 가능」).
"""

from __future__ import annotations

import inspect
import logging
from collections.abc import Awaitable, Callable

log = logging.getLogger(__name__)

Check = Callable[[], bool | Awaitable[bool]]


class ReadinessRegistry:
    def __init__(self) -> None:
        self._checks: dict[str, Check] = {}

    def register(self, name: str, check: Check) -> None:
        self._checks[name] = check

    async def evaluate(self) -> tuple[bool, dict[str, str]]:
        components: dict[str, str] = {}
        for name, check in self._checks.items():
            try:
                result = check()
                if inspect.isawaitable(result):
                    result = await result
                components[name] = "UP" if result else "DOWN"
            except Exception:  # 점검 자체의 오류도 not ready로 본다
                log.exception("readiness check failed: %s", name)
                components[name] = "DOWN"
        return all(v == "UP" for v in components.values()), components
