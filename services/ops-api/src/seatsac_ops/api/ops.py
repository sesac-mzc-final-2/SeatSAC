"""/ops/** 8개 (contracts/openapi/ops.openapi.yaml — OPS-20~24). 모두 ADMIN 그룹만."""

from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import JSONResponse

from seatsac_ops.audit import audit
from seatsac_ops.auth import AdminUser, require_admin
from seatsac_ops.common.errors import ApiError, ErrorCode, ErrorDetail
from seatsac_ops.common.metrics import record_action
from seatsac_ops.reports import ReportService
from seatsac_ops.upstream import UpstreamClient
from seatsac_ops.workloads import WorkloadService

router = APIRouter(prefix="/ops")

# 인증 의존성(모듈 하나에 하나씩 — 기본값 자리에서 함수를 부르지 않게)
ADMIN = require_admin()
STOP_ADMIN = require_admin("stop", "service")
START_ADMIN = require_admin("start", "service")
REPORT_ADMIN = require_admin("report")


def _workloads(request: Request) -> WorkloadService:
    return request.app.state.workloads


def _reports(request: Request) -> ReportService:
    return request.app.state.reports


def _upstream(request: Request) -> UpstreamClient:
    return request.app.state.upstream


# --- OPS-20·21 서비스 상태 · 정지/가동 ------------------------------------------


@router.get("/services")
async def list_services(
    _: AdminUser = Depends(ADMIN), workloads: WorkloadService = Depends(_workloads)
) -> dict[str, Any]:
    return await workloads.status()


async def _scale(
    action: str, service: str, user: AdminUser, workloads: WorkloadService
) -> JSONResponse:
    try:
        result = await (workloads.stop(service) if action == "stop" else workloads.start(service))
    except ApiError as exc:
        # 400·409는 관리자 화면의 정상적인 거절(denied), 503은 실패(error)
        outcome = "error" if exc.status >= 500 else "denied"
        audit(user.user_id, action, service[:32], outcome, code=exc.code.value)
        record_action(action, outcome)
        raise
    audit(
        user.user_id,
        action,
        service,
        "ok",
        desired=result["desired"],
        previous=result.get("previous"),
    )
    record_action(action, "ok")
    return JSONResponse(
        {"service": result["service"], "desired": result["desired"]}, status_code=202
    )


@router.post("/services/{service}/stop", status_code=202)
async def stop_service(
    service: str,
    user: AdminUser = Depends(STOP_ADMIN),
    workloads: WorkloadService = Depends(_workloads),
) -> JSONResponse:
    return await _scale("stop", service, user, workloads)


@router.post("/services/{service}/start", status_code=202)
async def start_service(
    service: str,
    user: AdminUser = Depends(START_ADMIN),
    workloads: WorkloadService = Depends(_workloads),
) -> JSONResponse:
    return await _scale("start", service, user, workloads)


# --- OPS-22 리포트 ------------------------------------------------------------
# dates가 {queryId}보다 먼저 와야 한다(같은 깊이의 경로)


@router.get("/reports/errors/dates")
async def list_report_dates(
    _: AdminUser = Depends(ADMIN), reports: ReportService = Depends(_reports)
) -> dict[str, Any]:
    return {"items": await reports.dates()}


@router.get("/reports/errors")
async def request_report(
    date: str | None = Query(None),
    user: AdminUser = Depends(REPORT_ADMIN),
    reports: ReportService = Depends(_reports),
) -> JSONResponse:
    day = date or ""
    try:
        status, body = await reports.request(day)
    except ApiError as exc:
        outcome = "error" if exc.status >= 500 else "denied"
        audit(user.user_id, "report", day[:10] or None, outcome, code=exc.code.value)
        record_action("report", outcome)
        raise
    audit(user.user_id, "report", day, "ok", cached=status == 200, queryId=body.get("queryId"))
    record_action("report", "ok")
    return JSONResponse(body, status_code=status)


@router.get("/reports/errors/{query_id}")
async def get_report(
    query_id: str,
    _: AdminUser = Depends(ADMIN),
    reports: ReportService = Depends(_reports),
) -> dict[str, Any]:
    return await reports.status(query_id)


# --- OPS-23 AI 범위 밖 질문 통계 -------------------------------------------------


@router.get("/ai/unmet-demand")
async def unmet_demand(
    request: Request,
    _: AdminUser = Depends(ADMIN),
    upstream: UpstreamClient = Depends(_upstream),
) -> JSONResponse:
    # 검증은 ai가 한다(계약: ai의 400을 그대로) → 아는 매개변수만 글자 그대로 넘긴다
    params = {k: v for k, v in request.query_params.items() if k in ("from", "to", "limit")}
    response = await upstream.unmet_demand(params)
    return JSONResponse(response.body, status_code=response.status)


# --- OPS-24 쿠폰 이벤트 ---------------------------------------------------------


@router.post("/coupons/events")
async def create_coupon_event(
    request: Request,
    user: AdminUser = Depends(ADMIN),
    upstream: UpstreamClient = Depends(_upstream),
) -> JSONResponse:
    try:
        body = json.loads(await request.body() or b"null")
    except ValueError as exc:
        raise ApiError(
            400,
            ErrorCode.VALIDATION_FAILED,
            "Malformed body",
            [ErrorDetail(reason="MALFORMED_BODY")],
        ) from exc
    if not isinstance(body, dict):
        raise ApiError(
            400,
            ErrorCode.VALIDATION_FAILED,
            "Malformed body",
            [ErrorDetail(reason="MALFORMED_BODY")],
        )
    try:
        response = await upstream.create_coupon_event(body, user.user_id)
    except ApiError as exc:
        # coupon 503은 커밋 뒤 응답만 잃었을 수 있다 — 결과 불확실(계약 OPS-24)
        audit(user.user_id, "coupon_event_create", None, "error", code=exc.code.value)
        raise
    if response.status == 201 and isinstance(response.body, dict):
        audit(user.user_id, "coupon_event_create", str(response.body.get("eventId")), "ok")
    else:
        audit(user.user_id, "coupon_event_create", None, "denied", status=response.status)
    return JSONResponse(response.body, status_code=response.status)


@router.get("/coupons/events")
async def list_coupon_events(
    request: Request,
    _: AdminUser = Depends(ADMIN),
    upstream: UpstreamClient = Depends(_upstream),
) -> JSONResponse:
    params = {k: v for k, v in request.query_params.items() if k == "state"}
    response = await upstream.list_coupon_events(params)
    return JSONResponse(response.body, status_code=response.status)
