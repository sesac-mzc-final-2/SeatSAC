from __future__ import annotations

from datetime import UTC, datetime

from botocore.stub import ANY

from tests.fakes import admin_headers

WG = "cinema-staging-reports"


def test_not_configured_is_503(client, app):
    app.state.reports._settings.reports_bucket = None
    r = client.get("/ops/reports/errors/dates", headers=admin_headers())
    assert r.status_code == 503 and r.json()["code"] == "REPORT_UNAVAILABLE"
    assert r.json()["details"][0]["reason"] == "NOT_CONFIGURED"


def test_dates_from_partitions_newest_first(client, aws):
    _, _, s3_stub, _ = aws
    s3_stub.add_response(
        "list_objects_v2",
        {
            "CommonPrefixes": [
                {"Prefix": "errors/dt=2026-11-23/"},
                {"Prefix": "errors/dt=2026-11-24/"},
                {"Prefix": "errors/tmp/"},
            ],
            "IsTruncated": False,
        },
        {"Bucket": "cinema-staging-reports-test", "Prefix": "errors/", "Delimiter": "/"},
    )
    r = client.get("/ops/reports/errors/dates", headers=admin_headers())
    assert r.json() == {"items": ["2026-11-24", "2026-11-23"]}


def test_s3_error_is_503(client, aws):
    _, _, s3_stub, _ = aws
    s3_stub.add_client_error("list_objects_v2", "AccessDenied")
    r = client.get("/ops/reports/errors/dates", headers=admin_headers())
    assert r.status_code == 503 and r.json()["details"][0]["reason"] == "AccessDenied"


def test_bad_date_is_400(client):
    for bad in ("", "2026-13-01", "2026-11-24' OR 1=1 --", "20261124"):
        r = client.get("/ops/reports/errors", params={"date": bad}, headers=admin_headers())
        assert r.status_code == 400 and r.json()["details"][0]["field"] == "date"


def _succeeded(query_id: str, workgroup: str = WG) -> dict:
    return {
        "QueryExecution": {
            "QueryExecutionId": query_id,
            "WorkGroup": workgroup,
            "Status": {
                "State": "SUCCEEDED",
                "SubmissionDateTime": datetime(2026, 11, 24, 5, 1, 50, tzinfo=UTC),
                "CompletionDateTime": datetime(2026, 11, 24, 5, 2, 11, tzinfo=UTC),
            },
            "ResultConfiguration": {
                "OutputLocation": f"s3://cinema-staging-reports-test/athena/{query_id}.csv"
            },
        }
    }


def test_request_status_and_cache(client, aws, caplog):
    _, _, s3_stub, athena_stub = aws
    caplog.set_level("INFO", logger="seatsac_ops.audit")
    athena_stub.add_response(
        "start_query_execution",
        {"QueryExecutionId": "q-1"},
        {
            "QueryString": ANY,
            "QueryExecutionContext": {"Database": "cinema_staging_reports"},
            "WorkGroup": WG,
            "ExecutionParameters": ["'2026-11-24'"],
        },
    )
    r = client.get("/ops/reports/errors", params={"date": "2026-11-24"}, headers=admin_headers())
    assert r.status_code == 202 and r.json() == {"queryId": "q-1", "state": "RUNNING"}
    assert [x for x in caplog.records if x.getMessage() == "ops audit"][-1].fields[
        "action"
    ] == "report"

    athena_stub.add_response(
        "get_query_execution",
        {
            "QueryExecution": {
                "QueryExecutionId": "q-1",
                "WorkGroup": WG,
                "Status": {"State": "RUNNING"},
            }
        },
    )
    assert client.get("/ops/reports/errors/q-1", headers=admin_headers()).json() == {
        "state": "RUNNING"
    }

    athena_stub.add_response("get_query_execution", _succeeded("q-1"))
    athena_stub.add_response(
        "get_query_runtime_statistics", {"QueryRuntimeStatistics": {"Rows": {"OutputRows": 128}}}
    )
    done = client.get("/ops/reports/errors/q-1", headers=admin_headers()).json()
    assert done["state"] == "SUCCEEDED" and done["rowCount"] == 128
    assert done["downloadUrl"].startswith("https://cinema-staging-reports-test.s3.")
    assert "X-Amz-Expires=300" in done["downloadUrl"] or "Expires=" in done["downloadUrl"]
    assert done["generatedAt"] == "2026-11-24T14:02:11+09:00"

    s3_stub.add_response("list_objects_v2", {"Contents": []})  # 그 뒤 새로 내보낸 파일 없음
    cached = client.get(
        "/ops/reports/errors", params={"date": "2026-11-24"}, headers=admin_headers()
    )
    assert (
        cached.status_code == 200 and cached.json()["rowCount"] == 128
    )  # 쿼리를 다시 돌리지 않는다
    athena_stub.assert_no_pending_responses()


def test_failed_unknown_and_other_workgroup(client, aws):
    _, _, _, athena_stub = aws
    athena_stub.add_response(
        "get_query_execution",
        {
            "QueryExecution": {
                "QueryExecutionId": "q-2",
                "WorkGroup": WG,
                "Status": {"State": "FAILED"},
            }
        },
    )
    assert client.get("/ops/reports/errors/q-2", headers=admin_headers()).json() == {
        "state": "FAILED"
    }
    athena_stub.add_client_error("get_query_execution", "InvalidRequestException")
    assert client.get("/ops/reports/errors/nope", headers=admin_headers()).status_code == 404
    # 리뷰 Minor 4 — 다른 작업 그룹의 ID는 IAM이 막는다(AccessDenied) → 503이 아니라 404
    athena_stub.add_client_error("get_query_execution", "AccessDeniedException")
    assert client.get("/ops/reports/errors/q-other", headers=admin_headers()).status_code == 404
    athena_stub.add_response("get_query_execution", _succeeded("q-3", workgroup="primary"))
    assert client.get("/ops/reports/errors/q-3", headers=admin_headers()).status_code == 404
    assert client.get("/ops/reports/errors/bad$id", headers=admin_headers()).status_code == 404


def test_presigned_url_uses_the_regional_endpoint(monkeypatch):
    # 전역 주소(…s3.amazonaws.com)면 새 버킷에서 us-east-1로 가 서명 리전 오류
    # — 리전 주소여야 한다 (6.1.9)
    from seatsac_ops.reports import s3_client

    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "test")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "test")
    url = s3_client("ap-northeast-2").generate_presigned_url(
        "get_object",
        Params={"Bucket": "cinema-staging-reports-x", "Key": "athena-results/q.csv"},
        ExpiresIn=300,
    )
    host = "https://cinema-staging-reports-x.s3.ap-northeast-2.amazonaws.com"
    assert url.startswith(f"{host}/athena-results/q.csv?")
    assert "ap-northeast-2%2Fs3%2Faws4_request" in url


def test_report_service_builds_its_s3_client_with_s3_client(monkeypatch, settings):
    # _clients()가 boto3 기본 클라이언트(전역 주소)로 되돌아가지 않게 — s3_client를 쓰는지 확인
    from seatsac_ops import reports

    made = []
    monkeypatch.setattr(reports, "s3_client", lambda region: made.append(region) or object())
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "test")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "test")
    s3, _ = reports.ReportService(settings)._clients()
    assert made == [settings.aws_region]
    assert s3 is not None


def _cache_q(athena_stub, query_id: str) -> None:
    athena_stub.add_response("start_query_execution", {"QueryExecutionId": query_id})
    athena_stub.add_response("get_query_execution", _succeeded(query_id))
    athena_stub.add_response(
        "get_query_runtime_statistics", {"QueryRuntimeStatistics": {"Rows": {"OutputRows": 4}}}
    )


def test_cache_is_dropped_when_a_newer_export_landed(client, aws):
    # 결과(제출 05:01:50Z) 뒤에 내보내기 파일이 새로 올라오면 1시간 안이라도 쿼리를 다시 돌린다
    # (11/27 「12:34에 만듦」만 뜸)
    _, _, s3_stub, athena_stub = aws
    _cache_q(athena_stub, "q-a")
    params = {"date": "2026-11-24"}
    assert (
        client.get("/ops/reports/errors", params=params, headers=admin_headers()).status_code == 202
    )
    assert (
        client.get("/ops/reports/errors/q-a", headers=admin_headers()).json()["state"]
        == "SUCCEEDED"
    )

    listing = {"Bucket": "cinema-staging-reports-test", "Prefix": "errors/dt=2026-11-24/"}
    older = datetime(2026, 11, 24, 5, 0, 0, tzinfo=UTC)
    s3_stub.add_response(
        "list_objects_v2",
        {"Contents": [{"Key": "errors/dt=2026-11-24/13.json.gz", "LastModified": older}]},
        listing,
    )
    cached = client.get("/ops/reports/errors", params=params, headers=admin_headers())
    assert cached.status_code == 200  # 그 뒤 새 파일 없음 → 캐시

    newer = datetime(2026, 11, 24, 5, 10, 0, tzinfo=UTC)
    s3_stub.add_response(
        "list_objects_v2",
        {"Contents": [{"Key": "errors/dt=2026-11-24/14.json.gz", "LastModified": newer}]},
        listing,
    )
    athena_stub.add_response("start_query_execution", {"QueryExecutionId": "q-b"})
    fresh = client.get("/ops/reports/errors", params=params, headers=admin_headers())
    assert fresh.status_code == 202 and fresh.json()["queryId"] == "q-b"
    s3_stub.assert_no_pending_responses()
    athena_stub.assert_no_pending_responses()


def _cached_report(client, athena_stub, query_id: str) -> dict:
    _cache_q(athena_stub, query_id)
    params = {"date": "2026-11-24"}
    assert (
        client.get("/ops/reports/errors", params=params, headers=admin_headers()).status_code == 202
    )
    return client.get(f"/ops/reports/errors/{query_id}", headers=admin_headers()).json()


def test_file_uploaded_while_the_query_ran_drops_the_cache(client, aws):
    # 제출(05:01:50Z) 뒤·완료(05:02:11Z) 전에 올라온 파일 — 완료 시각과 비교하면 놓친다(리뷰)
    _, _, s3_stub, athena_stub = aws
    assert _cached_report(client, athena_stub, "q-c")["state"] == "SUCCEEDED"
    during = datetime(2026, 11, 24, 5, 2, 0, tzinfo=UTC)
    s3_stub.add_response(
        "list_objects_v2",
        {"Contents": [{"Key": "errors/dt=2026-11-24/14.json.gz", "LastModified": during}]},
    )
    athena_stub.add_response("start_query_execution", {"QueryExecutionId": "q-d"})
    again = client.get(
        "/ops/reports/errors", params={"date": "2026-11-24"}, headers=admin_headers()
    )
    assert again.status_code == 202 and again.json()["queryId"] == "q-d"


def test_freshness_check_failure_still_serves_the_cache(client, aws):
    # 신선도 확인(S3 목록)이 실패해도 있는 결과까지 503으로 만들지 않는다(리뷰)
    _, _, s3_stub, athena_stub = aws
    assert _cached_report(client, athena_stub, "q-e")["state"] == "SUCCEEDED"
    s3_stub.add_client_error("list_objects_v2", "AccessDenied")
    cached = client.get(
        "/ops/reports/errors", params={"date": "2026-11-24"}, headers=admin_headers()
    )
    assert cached.status_code == 200 and cached.json()["rowCount"] == 4
    athena_stub.assert_no_pending_responses()
