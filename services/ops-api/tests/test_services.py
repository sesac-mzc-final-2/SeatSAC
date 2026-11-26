from __future__ import annotations

import pytest
from prometheus_client import REGISTRY

from tests.fakes import admin_headers, deployment, hpa, rollout


@pytest.fixture
def cluster(kube, prom):
    kube.rollouts.update(gateway=rollout("gateway", 2), booking=rollout("booking", 2))
    kube.deployments.update(
        catalog=deployment("catalog", 2, git=2),
        payment=deployment("payment", 2, git=2),
        **{"ops-api": deployment("ops-api", 1)},
    )
    kube.hpas.update(booking=hpa("booking", 2, 4, 2, 37), catalog=hpa("catalog", 2, 4, 2, None))
    kube.nodes = [
        {
            "metadata": {
                "name": "ip-10-20-11-5",
                "labels": {
                    "topology.kubernetes.io/zone": "ap-northeast-2a",
                    "karpenter.sh/capacity-type": "on-demand",
                },
            }
        },
        {
            "metadata": {
                "name": "ip-10-20-12-7",
                "labels": {
                    "topology.kubernetes.io/zone": "ap-northeast-2c",
                    "eks.amazonaws.com/capacityType": "SPOT",
                },
            }
        },
    ]
    prom.series = {
        "cinema:service_state": [({"application": "gateway"}, 0), ({"application": "catalog"}, 1)],
        "container_cpu_usage_seconds_total": [({"node": "ip-10-20-11-5"}, 37.4)],
        "container_memory_working_set_bytes": [({"node": "ip-10-20-11-5"}, 61.6)],
    }
    return kube


def test_status_lists_deployed_services_with_metrics(client, cluster):
    r = client.get("/ops/services", headers=admin_headers())
    assert r.status_code == 200
    body = r.json()
    by_name = {item["service"]: item for item in body["items"]}
    assert list(by_name) == [
        "gateway",
        "booking",
        "catalog",
        "payment",
        "ops-api",
    ]  # 없는 서비스는 빠진다
    assert by_name["gateway"] == {
        "service": "gateway",
        "kind": "ROLLOUT",
        "desired": 2,
        "ready": 2,
        "state": 0,
        "hpa": None,
        "stopped": False,
    }
    assert by_name["booking"]["hpa"] == {"min": 2, "max": 4, "current": 2, "cpuPercent": 37}
    assert by_name["catalog"]["state"] == 1 and by_name["catalog"]["hpa"]["cpuPercent"] is None
    assert body["nodes"][0] == {
        "name": "ip-10-20-11-5",
        "zone": "ap-northeast-2a",
        "cpuPercent": 37,
        "memoryPercent": 62,
        "capacityType": "ON_DEMAND",
    }
    assert body["nodes"][1]["capacityType"] == "SPOT" and body["nodes"][1]["cpuPercent"] is None
    assert body["asOf"].endswith("+09:00")


def test_status_without_prometheus_keeps_k8s_fields(client, cluster, prom):
    prom.fail = True
    body = client.get("/ops/services", headers=admin_headers()).json()
    assert all(item["state"] is None for item in body["items"])
    assert body["items"][0]["desired"] == 2


def test_status_kubernetes_down_is_503(client, cluster):
    cluster.fail = True
    r = client.get("/ops/services", headers=admin_headers())
    assert r.status_code == 503
    assert r.json()["details"][0]["value"] == "kubernetes"


def test_stop_remembers_then_scales_to_zero_and_is_idempotent(client, cluster, caplog):
    caplog.set_level("INFO", logger="seatsac_ops.audit")
    r = client.post("/ops/services/catalog/stop", headers=admin_headers())
    assert r.status_code == 202 and r.json() == {"service": "catalog", "desired": 0}
    assert cluster.state == {"catalog": "2"}
    patches = [c for c in cluster.calls if c[0] == "PATCH"]
    assert patches[0][1].endswith("/configmaps/ops-api-state")  # 먼저 기록
    assert patches[1][1].endswith("/deployments/catalog/scale")  # 그다음 0
    assert cluster.deployments["catalog"]["spec"]["replicas"] == 0
    audit = [r for r in caplog.records if r.getMessage() == "ops audit"][-1]
    assert audit.fields["result"] == "ok" and audit.fields["previous"] == 2

    again = client.post("/ops/services/catalog/stop", headers=admin_headers())
    assert again.status_code == 202 and again.json()["desired"] == 0
    assert cluster.state == {"catalog": "2"}  # 0을 덮어쓰지 않는다

    status = client.get("/ops/services", headers=admin_headers()).json()
    catalog = next(i for i in status["items"] if i["service"] == "catalog")
    assert catalog["stopped"] is True and catalog["desired"] == 0


def test_start_restores_remembered_value(client, cluster):
    cluster.deployments["payment"]["spec"]["replicas"] = 3
    client.post("/ops/services/payment/stop", headers=admin_headers())
    r = client.post("/ops/services/payment/start", headers=admin_headers())
    assert r.status_code == 202 and r.json() == {"service": "payment", "desired": 3}
    assert "payment" not in cluster.state
    assert (
        client.post("/ops/services/payment/start", headers=admin_headers()).json()["desired"] == 3
    )


def test_start_is_202_even_if_clearing_the_memory_fails(client, cluster, caplog):
    # 리뷰 Minor 2 — 가동은 됐는데 기록 지우기만 실패하면 503이 아니라 202(경고 로그).
    # 남은 기록은 다음 정지 때 덮어쓴다
    cluster.deployments["payment"]["spec"]["replicas"] = 3
    client.post("/ops/services/payment/stop", headers=admin_headers())
    cluster.fail_state_clear = True
    caplog.set_level("WARNING", logger="seatsac_ops.workloads")
    r = client.post("/ops/services/payment/start", headers=admin_headers())
    assert r.status_code == 202 and r.json() == {"service": "payment", "desired": 3}
    assert cluster.deployments["payment"]["spec"]["replicas"] == 3
    assert any("정지 기록을 지우지 못했다" in x.getMessage() for x in caplog.records)


def test_start_without_memory_uses_git_then_hpa_min(client, cluster):
    cluster.deployments["catalog"]["spec"]["replicas"] = 0
    assert (
        client.post("/ops/services/catalog/start", headers=admin_headers()).json()["desired"] == 2
    )
    cluster.rollouts["booking"]["spec"]["replicas"] = (
        0  # Rollout에는 last-applied가 없다 → HPA 최소
    )
    assert (
        client.post("/ops/services/booking/start", headers=admin_headers()).json()["desired"] == 2
    )
    assert cluster.rollouts["booking"]["spec"]["replicas"] == 2
    cluster.rollouts["gateway"]["spec"]["replicas"] = 0  # 둘 다 없으면 1
    assert (
        client.post("/ops/services/gateway/start", headers=admin_headers()).json()["desired"] == 1
    )


@pytest.mark.parametrize("name", ["ops-api", "argocd-server", "x" * 40])
def test_not_stoppable(client, cluster, name):
    for action in ("stop", "start"):
        r = client.post(f"/ops/services/{name}/{action}", headers=admin_headers())
        assert r.status_code == 400 and r.json()["code"] == "SERVICE_NOT_STOPPABLE"
        assert len(r.json()["details"][0]["value"]) <= 32


def test_blue_green_in_progress_is_409(client, cluster):
    before = _denied()
    cluster.rollouts["booking"] = rollout("booking", 2, busy=True)
    r = client.post("/ops/services/booking/stop", headers=admin_headers())
    assert r.status_code == 409 and r.json()["code"] == "ROLLOUT_IN_PROGRESS"
    assert cluster.rollouts["booking"]["spec"]["replicas"] == 2
    assert _denied() == before + 1


def test_not_deployed_and_missing_state_configmap_are_503(client, cluster):
    r = client.post("/ops/services/coupon/stop", headers=admin_headers())
    assert r.status_code == 503 and r.json()["details"][0]["reason"] == "NOT_DEPLOYED"
    cluster.state = None
    r = client.post("/ops/services/catalog/stop", headers=admin_headers())
    assert r.status_code == 503 and r.json()["details"][0]["reason"] == "STATE_CONFIGMAP_MISSING"
    assert cluster.deployments["catalog"]["spec"]["replicas"] == 2  # 기록 실패면 scale하지 않는다


def _denied() -> float:
    return (
        REGISTRY.get_sample_value("ops_action_total", {"action": "stop", "result": "denied"}) or 0.0
    )
