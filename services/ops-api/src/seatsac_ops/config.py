"""설정. 이름·기본값은 docs/service-runtime-contract.md 10장과 conventions 8장(`OPS_*`).

엔드포인트 키(PROMETHEUS_URL·REPORTS_BUCKET·ATHENA_WORKGROUP·COGNITO_*)는 Terraform ConfigMap
`cinema-infra-endpoints`의 이름을 그대로 읽는다(Python은 `${}` 치환이 없다 — ai와 같은 방식).
"""

from __future__ import annotations

import re
from functools import lru_cache
from typing import Literal

from pydantic import AliasChoices, Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_IDENT = re.compile(r"^[a-z0-9_]+$")


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", populate_by_name=True)

    # 프로필: local(사람이 읽는 로그) | staging(JSON 로그). 이미지 기본값은 staging(Dockerfile
    # APP_ENV)
    app_env: Literal["local", "staging"] = Field(
        "local", validation_alias=AliasChoices("OPS_ENV", "APP_ENV")
    )

    # 인증 — Cognito Access Token (ADR-119). gateway와 같은 사용자 풀·앱 클라이언트
    cognito_issuer_uri: str = Field("", validation_alias="COGNITO_ISSUER_URI")
    cognito_client_id: str = Field("", validation_alias="COGNITO_CLIENT_ID")
    admin_group: str = Field("ADMIN", validation_alias="OPS_ADMIN_GROUP")
    jwks_timeout_seconds: float = Field(3.0, validation_alias="OPS_JWKS_TIMEOUT_SECONDS")

    # Kubernetes API (파드 ServiceAccount 토큰 — Role은 gitops base/ops-api/rbac.yaml)
    k8s_api_url: str = Field("https://kubernetes.default.svc", validation_alias="OPS_K8S_API_URL")
    k8s_namespace: str = Field("cinema-staging", validation_alias="OPS_K8S_NAMESPACE")
    k8s_token_path: str = Field(
        "/var/run/secrets/kubernetes.io/serviceaccount/token", validation_alias="OPS_K8S_TOKEN_PATH"
    )
    k8s_ca_path: str = Field(
        "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt", validation_alias="OPS_K8S_CA_PATH"
    )
    k8s_timeout_seconds: float = Field(3.0, validation_alias="OPS_K8S_TIMEOUT_SECONDS")
    # 정지 전 replicas를 적어 두는 ConfigMap(gitops base/ops-api/state-configmap.yaml, data는
    # ArgoCD가 비교하지 않는다)
    state_configmap: str = Field("ops-api-state", validation_alias="OPS_STATE_CONFIGMAP")

    # Prometheus (cinema-infra-endpoints PROMETHEUS_URL). 없거나 실패하면 지표 칸만 null
    prometheus_url: str | None = Field(None, validation_alias="PROMETHEUS_URL")
    prometheus_timeout_seconds: float = Field(
        2.0, validation_alias="OPS_PROMETHEUS_TIMEOUT_SECONDS"
    )

    # 리포트 (6.1.8·6.1.9 — 버킷·작업 그룹이 생기기 전에는 비어 있어 503 REPORT_UNAVAILABLE)
    aws_region: str = Field("ap-northeast-2", validation_alias="AWS_REGION")
    reports_bucket: str | None = Field(None, validation_alias="REPORTS_BUCKET")
    athena_workgroup: str | None = Field(None, validation_alias="ATHENA_WORKGROUP")
    report_database: str = Field("cinema_staging_reports", validation_alias="OPS_REPORT_DATABASE")
    report_table: str = Field("errors", validation_alias="OPS_REPORT_TABLE")
    report_cache_seconds: int = Field(3600, validation_alias="OPS_REPORT_CACHE_SECONDS")
    report_url_seconds: int = Field(300, validation_alias="OPS_REPORT_URL_SECONDS")

    # 하위 내부 API (contracts.md 내부 호출 표: 연결 1초 · 응답 3초, 재시도 없음)
    services_ai_url: str = Field("http://ai:8080", validation_alias="OPS_SERVICES_AI_URL")
    services_coupon_url: str = Field(
        "http://coupon:8080", validation_alias="OPS_SERVICES_COUPON_URL"
    )
    services_connect_timeout: float = Field(1.0, validation_alias="OPS_SERVICES_CONNECT_TIMEOUT")
    services_read_timeout: float = Field(3.0, validation_alias="OPS_SERVICES_READ_TIMEOUT")

    @field_validator("report_database", "report_table")
    @classmethod
    def _identifier(cls, value: str) -> str:
        # Athena 쿼리 문자열에 들어간다 — 소문자·숫자·밑줄만(사용자 입력이 아니라 설정이지만 막아
        # 둔다)
        if not _IDENT.fullmatch(value):
            raise ValueError(f"invalid identifier: {value!r}")
        return value

    @field_validator("prometheus_url", "reports_bucket", "athena_workgroup", mode="before")
    @classmethod
    def _blank_is_none(cls, value: object) -> object:
        return None if isinstance(value, str) and not value.strip() else value


@lru_cache
def get_settings() -> Settings:
    return Settings()
