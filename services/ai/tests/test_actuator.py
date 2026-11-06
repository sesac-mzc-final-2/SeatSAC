def test_liveness_is_up(client):
    r = client.get("/actuator/health/liveness")
    assert r.status_code == 200
    assert r.json() == {"status": "UP"}


def test_readiness_is_up_when_no_checks_registered(client):
    # 골격 단계: 스냅샷·Valkey 점검이 아직 없어서 UP (3.4.9·3.4.5가 등록)
    r = client.get("/actuator/health/readiness")
    assert r.status_code == 200
    assert r.json()["status"] == "UP"


def test_readiness_is_503_when_a_check_fails(app, client):
    app.state.readiness.register("snapshot", lambda: False)
    r = client.get("/actuator/health/readiness")
    assert r.status_code == 503
    assert r.json() == {"status": "OUT_OF_SERVICE", "components": {"snapshot": "DOWN"}}


def test_readiness_treats_check_exception_as_down(app, client):
    def boom() -> bool:
        raise RuntimeError("valkey down")

    app.state.readiness.register("valkey", boom)
    assert client.get("/actuator/health/readiness").status_code == 503


def test_readiness_supports_async_checks(app, client):
    async def ok() -> bool:
        return True

    app.state.readiness.register("snapshot", ok)
    assert client.get("/actuator/health/readiness").status_code == 200


def test_liveness_ignores_failing_readiness_checks(app, client):
    app.state.readiness.register("valkey", lambda: False)
    assert client.get("/actuator/health/liveness").status_code == 200
