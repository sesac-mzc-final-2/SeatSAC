from __future__ import annotations

import boto3
import pytest
from botocore.stub import Stubber
from fastapi.testclient import TestClient

from seatsac_ops.auth import TokenVerifier
from seatsac_ops.config import Settings
from seatsac_ops.kube import KubeClient
from seatsac_ops.main import create_app
from seatsac_ops.prom import PromClient
from seatsac_ops.reports import ReportService
from seatsac_ops.upstream import UpstreamClient
from tests.fakes import CLIENT_ID, ISSUER, FakeJwkClient, FakeKube, FakeProm, FakeUpstream


@pytest.fixture
def settings(tmp_path) -> Settings:
    (tmp_path / "token").write_text("sa-token")
    return Settings(
        APP_ENV="staging",
        COGNITO_ISSUER_URI=ISSUER,
        COGNITO_CLIENT_ID=CLIENT_ID,
        PROMETHEUS_URL="http://prometheus:9090",
        REPORTS_BUCKET="cinema-staging-reports-test",
        ATHENA_WORKGROUP="cinema-staging-reports",
        OPS_K8S_TOKEN_PATH=str(tmp_path / "token"),
        OPS_K8S_CA_PATH=str(tmp_path / "missing-ca"),
    )


@pytest.fixture
def jwk() -> FakeJwkClient:
    return FakeJwkClient()


@pytest.fixture
def kube() -> FakeKube:
    return FakeKube()


@pytest.fixture
def prom() -> FakeProm:
    return FakeProm()


@pytest.fixture
def upstream() -> FakeUpstream:
    return FakeUpstream()


@pytest.fixture
def aws():
    s3 = boto3.client(
        "s3", region_name="ap-northeast-2", aws_access_key_id="x", aws_secret_access_key="x"
    )
    athena = boto3.client(
        "athena", region_name="ap-northeast-2", aws_access_key_id="x", aws_secret_access_key="x"
    )
    s3_stub, athena_stub = Stubber(s3), Stubber(athena)
    s3_stub.activate()
    athena_stub.activate()
    yield s3, athena, s3_stub, athena_stub
    s3_stub.deactivate()
    athena_stub.deactivate()


@pytest.fixture
def app(settings, jwk, kube, prom, upstream, aws):
    s3, athena, _, _ = aws
    return create_app(
        settings,
        verifier=TokenVerifier(settings, jwk_client=jwk),
        kube=KubeClient(settings, transport=kube.transport()),
        prom=PromClient(settings, transport=prom.transport()),
        reports=ReportService(settings, s3=s3, athena=athena),
        upstream=UpstreamClient(settings, transport=upstream.transport()),
    )


@pytest.fixture
def client(app):
    with TestClient(app) as c:
        yield c
