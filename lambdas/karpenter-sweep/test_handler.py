"""밤 정리 Lambda 단위 테스트 (표준 라이브러리만 — boto3는 가짜로 바꾼다).

실행: python3 -m unittest discover -s lambdas/karpenter-sweep -v
"""
import contextlib
import io
import os
import sys
import types
import unittest

sys.modules.setdefault("boto3", types.ModuleType("boto3"))   # CI에는 boto3가 없다 — handler는 client를 주입받는다
import handler  # noqa: E402


class FakeEks:
    def __init__(self, desired):
        self.desired = desired

    def list_nodegroups(self, clusterName, nextToken=None):
        return {"nodegroups": list(self.desired)}

    def describe_nodegroup(self, clusterName, nodegroupName):
        return {"nodegroup": {"scalingConfig": {"desiredSize": self.desired[nodegroupName]}}}


class FakeEc2:
    def __init__(self, ids):
        self.ids, self.terminated, self.filters = ids, [], None

    def get_paginator(self, name):
        outer = self

        class Pager:
            def paginate(self, Filters):
                outer.filters = Filters
                return [{"Reservations": [{"Instances": [{"InstanceId": i} for i in outer.ids]}]}]
        return Pager()

    def terminate_instances(self, InstanceIds):
        self.terminated += InstanceIds


def run(desired, ids, event=None):
    eks, ec2 = FakeEks(desired), FakeEc2(ids)
    with contextlib.redirect_stdout(io.StringIO()):
        result = handler.handler(event or {}, None, eks=eks, ec2=ec2)
    return result, ec2


class SweepTest(unittest.TestCase):
    def setUp(self):
        os.environ["CLUSTER_NAME"] = "cinema-staging-eks"
        os.environ.pop("DRY_RUN", None)

    def test_managed_nodes_on_means_nothing_is_terminated(self):
        result, ec2 = run({"ng-a": 1, "ng-c": 0, "ng-ai": 0}, ["i-1"])
        self.assertEqual(result["action"], "skip")
        self.assertEqual(result["nodegroups"], {"ng-a": 1})
        self.assertEqual(ec2.terminated, [])
        self.assertIsNone(ec2.filters)   # 인스턴스 조회도 하지 않는다

    def test_all_managed_zero_terminates_karpenter_nodes_only_by_tag(self):
        result, ec2 = run({"ng-a": 0, "ng-c": 0, "ng-ai": 0}, ["i-1", "i-2"])
        self.assertEqual(result["action"], "terminated")
        self.assertEqual(ec2.terminated, ["i-1", "i-2"])
        names = {f["Name"]: f["Values"] for f in ec2.filters}
        self.assertEqual(names["tag:kubernetes.io/cluster/cinema-staging-eks"], ["owned"])
        self.assertEqual(names["tag-key"], ["karpenter.sh/nodepool"])

    def test_dry_run_lists_without_terminating(self):
        result, ec2 = run({"ng-a": 0, "ng-c": 0}, ["i-1"], {"dryRun": True})
        self.assertEqual(result, {"action": "dry_run", "instanceIds": ["i-1"]})
        self.assertEqual(ec2.terminated, [])

    def test_no_karpenter_nodes(self):
        result, ec2 = run({"ng-a": 0, "ng-c": 0}, [])
        self.assertEqual(result["action"], "none")
        self.assertEqual(ec2.terminated, [])

    def test_no_nodegroups_is_skip(self):
        result, ec2 = run({}, ["i-1"])
        self.assertEqual(result["reason"], "NO_NODEGROUPS")
        self.assertEqual(ec2.terminated, [])


if __name__ == "__main__":
    unittest.main()
