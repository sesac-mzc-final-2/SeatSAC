# ------------------------------------------------------------------
# 6.1.6 Karpenter 컨트롤러 (ADR-122)
#   - 권한·노드 역할·중단 알림 큐는 envs/staging/karpenter.tf(먼저 apply). 여기서는 같은 이름의 ServiceAccount로 설치만
#   - Terraform으로 설치하는 이유: 차트가 OCI(public.ecr.aws)라 ArgoCD에 OCI 저장소 등록이 따로 필요하고,
#     settings(clusterName·interruptionQueue)가 Terraform 값이다. NodePool·EC2NodeClass(CRD를 쓰는 쪽)는 gitops platform/karpenter
#   - CRD는 차트 crds/에 있어 **처음 설치 때만** 들어간다(helm은 crds/를 올리지 않는다). 차트를 올릴 때는 런북 6-K 7) 순서
#   - 컨트롤러는 관리형 노드(ng-a·ng-c)에만 뜬다 — 차트 기본 affinity(karpenter.sh/nodepool DoesNotExist): 자기가 만든 노드를
#     스스로 지우다 멈추지 않게. 그래서 밤에 관리형 노드그룹이 0대면 컨트롤러도 멈춘다 → 남은 노드는 envs/staging 정리 Lambda가 지운다
#   - 버전: Kubernetes 1.36은 Karpenter ≥ 1.13 (공식 호환표). 1.15.3 = 2026-11-20 릴리스 — apply 전 helm show chart로 다시 확인
# ------------------------------------------------------------------
locals {
  karpenter_queue_name = "${trimsuffix(var.cluster_name, "-eks")}-karpenter" # cinema-staging-karpenter (envs/staging karpenter.tf queue_name)
}

data "aws_sqs_queue" "karpenter" {
  count = var.karpenter_enabled ? 1 : 0
  name  = local.karpenter_queue_name # 없으면 plan이 멈춘다 → envs/staging을 먼저 apply
}

resource "helm_release" "karpenter" {
  count = var.karpenter_enabled ? 1 : 0

  name             = "karpenter"
  namespace        = "karpenter" # envs/staging Pod Identity 연결(karpenter/karpenter)과 같은 값
  create_namespace = true
  repository       = "oci://public.ecr.aws/karpenter"
  chart            = "karpenter"
  version          = var.karpenter_chart_version

  wait    = true
  timeout = 600

  values = [yamlencode({
    settings = {
      clusterName       = var.cluster_name
      interruptionQueue = data.aws_sqs_queue.karpenter[0].name
    }

    serviceAccount = {
      create = true
      name   = "karpenter" # Pod Identity 연결과 같은 이름
    }

    # 관리형 노드 2대(AZ a·c)에 하나씩(차트 기본 hostname anti-affinity·zone 분산). 하나가 죽어도 리더 선출로 이어 간다
    replicas = var.karpenter_replicas

    controller = {
      resources = {
        requests = { cpu = "100m", memory = "256Mi" } # 차트 예시 1 vCPU·1Gi는 노드 수백 대 기준. 우리는 수 대
        limits   = { memory = "512Mi" }
      }
    }
  })]
}
