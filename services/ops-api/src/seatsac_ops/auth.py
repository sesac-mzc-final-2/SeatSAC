"""관리자 인증 — Cognito Access Token을 ops-api가 직접 검증한다(ADR-119, ops.openapi.yaml).

gateway를 거치지 않으므로 X-User-* 헤더를 쓰지 않는다. 검증: JWKS(RS256) 서명 · exp · iss =
COGNITO_ISSUER_URI ·
token_use = access · client_id = COGNITO_CLIENT_ID(ID 토큰·다른 앱 클라이언트 토큰은 거절) →
`cognito:groups` ∋ ADMIN.
- 토큰 없음 401 TOKEN_REQUIRED · 그 밖의 검증 실패 401 INVALID_TOKEN · ADMIN 아님 403 FORBIDDEN
- JWKS를 받지 못함(Cognito·NAT 장애) 503 SERVICE_UNAVAILABLE upstream cognito — 토큰 탓이 아니라서
  401로 바꾸지 않는다
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

import jwt
from fastapi import Request
from starlette.concurrency import run_in_threadpool

from seatsac_ops.audit import audit
from seatsac_ops.common.errors import ApiError, forbidden, unauthorized, upstream_unavailable
from seatsac_ops.common.metrics import record_action
from seatsac_ops.common.trace import user_id_var
from seatsac_ops.config import Settings

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class AdminUser:
    user_id: str


class TokenVerifier:
    """JWKS는 PyJWKClient가 1시간 캐시한다(키 교체 시 kid가 없으면 다시 받는다)."""

    def __init__(self, settings: Settings, jwk_client: Any | None = None) -> None:
        self._issuer = settings.cognito_issuer_uri.rstrip("/")
        self._client_id = settings.cognito_client_id
        self._admin_group = settings.admin_group
        self._jwk_client = jwk_client
        if self._jwk_client is None and self._issuer:
            self._jwk_client = jwt.PyJWKClient(
                f"{self._issuer}/.well-known/jwks.json",
                cache_keys=True,
                lifespan=3600,
                timeout=int(max(1, settings.jwks_timeout_seconds)),
            )

    def verify(self, token: str) -> dict[str, Any]:
        """동기 — 부르는 쪽이 스레드풀에서 돌린다(JWKS 첫 조회가 네트워크)."""
        if not self._issuer or not self._client_id or self._jwk_client is None:
            # Cognito 설정 전(cinema-infra-endpoints가 빈 값) — 아무 토큰도 받지 않는다
            raise upstream_unavailable("cognito", "NOT_CONFIGURED")
        try:
            signing_key = self._jwk_client.get_signing_key_from_jwt(token)
        except jwt.PyJWKClientConnectionError as exc:
            log.warning("JWKS를 받지 못했다: %s", type(exc).__name__)
            raise upstream_unavailable("cognito", "JWKS_UNAVAILABLE") from exc
        except (jwt.PyJWKClientError, jwt.InvalidTokenError) as exc:
            raise unauthorized("INVALID_TOKEN") from exc
        try:
            claims = jwt.decode(
                token,
                signing_key.key,
                algorithms=["RS256"],
                issuer=self._issuer,
                options={
                    "require": ["exp", "iss", "sub", "token_use", "client_id"],
                    "verify_aud": False,  # Access Token에는 aud가 없다 — client_id로 대신 본다
                },
                leeway=30,
            )
        except jwt.InvalidTokenError as exc:
            raise unauthorized("INVALID_TOKEN") from exc
        if claims.get("token_use") != "access" or claims.get("client_id") != self._client_id:
            raise unauthorized("INVALID_TOKEN")
        return claims

    def is_admin(self, claims: dict[str, Any]) -> bool:
        groups = claims.get("cognito:groups") or []
        return isinstance(groups, list) and self._admin_group in groups


def _bearer(request: Request) -> str | None:
    header = request.headers.get("Authorization") or ""
    scheme, _, value = header.partition(" ")
    if scheme.lower() != "bearer" or not value.strip():
        return None
    return value.strip()


def require_admin(
    action: str | None = None, target_param: str | None = None
) -> Callable[[Request], Awaitable[AdminUser]]:
    """FastAPI 의존성.

    action을 주면(정지·가동·리포트) 403도 감사 로그·ops_action_total{result=denied}에 남긴다.
    """

    async def dependency(request: Request) -> AdminUser:
        verifier: TokenVerifier = request.app.state.verifier
        token = _bearer(request)
        if token is None:
            raise unauthorized("TOKEN_REQUIRED")
        claims = await run_in_threadpool(verifier.verify, token)
        user_id = str(claims.get("sub"))
        user_id_var.set(user_id)
        if not verifier.is_admin(claims):
            if action is not None:
                target = request.path_params.get(target_param) if target_param else None
                audit(user_id, action, target, "denied")
                record_action(action, "denied")
            raise forbidden()
        return AdminUser(user_id=user_id)

    return dependency


__all__ = ["AdminUser", "ApiError", "TokenVerifier", "require_admin"]
