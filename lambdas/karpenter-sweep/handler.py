"""6.1.6 Karpenter 노드 밤 정리 (ADR-122, 런북 6-K-4).

EventBridge Scheduler가 15분마다 부른다. 클러스터의 관리형 노드그룹 desired가 **모두 0**일 때만
Karpenter가 만든 인스턴스(태그 kubernetes.io/cluster/{클러스터} = owned + karpenter.sh/nodepool)를 지운다.

왜 필요한가: Karpenter 컨트롤러는 관리형 노드(ng-a·ng-c)에만 뜬다. 밤에 관리형 노드그룹을 0대로 내리면
컨트롤러도 멈춰 Karpenter 노드를 아무도 지우지 않는다. 관리형 노드그룹이 하나라도 켜져 있으면(낮·데모 주간
24시간 가동·사람이 켠 때) 아무것도 하지 않는다 — 그때는 컨트롤러가 노드를 관리한다.

로그는 JSON 한 줄. 입력 {"dryRun": true}이면 지울 대상만 적고 지우지 않는다.
"""
import json
import os
from datetime import datetime, timedelta, timezone

import boto3

KST = timezone(timedelta(hours=9))
LIVE_STATES = ["pending", "running", "stopping", "stopped"]


def log(level, message, **fields):
    print(json.dumps({"timestamp": datetime.now(KST).isoformat(timespec="milliseconds"), "level": level,
                      "service": "karpenter-sweep", "message": message, **fields}, ensure_ascii=False))


def managed_desired(eks, cluster):
    """노드그룹 이름 → desired. 하나라도 못 읽으면 예외(지우지 않는 쪽으로 멈춘다)."""
    names, token = [], None
    while True:
        page = eks.list_nodegroups(clusterName=cluster, **({"nextToken": token} if token else {}))
        names += page.get("nodegroups", [])
        token = page.get("nextToken")
        if not token:
            break
    return {n: eks.describe_nodegroup(clusterName=cluster, nodegroupName=n)["nodegroup"]["scalingConfig"]["desiredSize"]
            for n in names}


def karpenter_instances(ec2, cluster):
    ids = []
    pages = ec2.get_paginator("describe_instances").paginate(Filters=[
        {"Name": f"tag:kubernetes.io/cluster/{cluster}", "Values": ["owned"]},
        {"Name": "tag-key", "Values": ["karpenter.sh/nodepool"]},
        {"Name": "instance-state-name", "Values": LIVE_STATES},
    ])
    for page in pages:
        for reservation in page.get("Reservations", []):
            ids += [i["InstanceId"] for i in reservation.get("Instances", [])]
    return ids


def sweep(eks, ec2, cluster, dry_run):
    desired = managed_desired(eks, cluster)
    if not desired:
        log("WARN", "관리형 노드그룹이 없다 — 컨트롤러가 뜰 곳을 알 수 없어 지우지 않는다", cluster=cluster)
        return {"action": "skip", "reason": "NO_NODEGROUPS"}
    on = {n: d for n, d in desired.items() if d > 0}
    if on:
        return {"action": "skip", "reason": "MANAGED_NODES_ON", "nodegroups": on}
    ids = karpenter_instances(ec2, cluster)
    if not ids:
        return {"action": "none", "reason": "NO_KARPENTER_NODES"}
    if dry_run:
        log("INFO", "dry-run — 지울 Karpenter 노드", cluster=cluster, instanceIds=ids)
        return {"action": "dry_run", "instanceIds": ids}
    ec2.terminate_instances(InstanceIds=ids)
    log("INFO", "관리형 노드그룹이 모두 0대 — Karpenter 노드를 지웠다", cluster=cluster, instanceIds=ids)
    return {"action": "terminated", "instanceIds": ids}


def handler(event, context, eks=None, ec2=None):
    cluster = os.environ["CLUSTER_NAME"]
    dry_run = os.environ.get("DRY_RUN", "false").lower() == "true" or bool((event or {}).get("dryRun"))
    result = sweep(eks or boto3.client("eks"), ec2 or boto3.client("ec2"), cluster, dry_run)
    if result["action"] != "terminated":
        log("INFO", "정리 결과", cluster=cluster, **result)
    return result
