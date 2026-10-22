# ------------------------------------------------------------------
# AWS Load Balancer Controller (3.1.2)
#   - IAM 권한은 envs/staging의 Pod Identity(cinema-staging-lb-controller-pod,
#     kube-system/aws-load-balancer-controller). 여기서는 같은 이름의 ServiceAccount로 설치만
#   - Terraform으로 설치하는 이유: gateway Ingress(ArgoCD, 3.1.5)보다 먼저 있어야 하고,
#     clusterName·vpcId가 Terraform 값이라서. 삭제 시에도 ALB를 먼저 정리할 수 있다
#   - ALB는 gateway Ingress가 만들 때 생긴다. Ingress 주석(3.1.5에서 사용):
#       alb.ingress.kubernetes.io/scheme: internet-facing
#       alb.ingress.kubernetes.io/target-type: ip
#       alb.ingress.kubernetes.io/security-groups: <envs/staging 출력 sg_alb_id>
#       alb.ingress.kubernetes.io/manage-backend-security-group-rules: "false"
#       alb.ingress.kubernetes.io/healthcheck-path: /actuator/health/readiness
#       alb.ingress.kubernetes.io/load-balancer-attributes: idle_timeout.timeout_seconds=120
#       alb.ingress.kubernetes.io/listen-ports: '[{"HTTP": 80}]'
# ------------------------------------------------------------------
resource "helm_release" "aws_load_balancer_controller" {
  name       = "aws-load-balancer-controller"
  namespace  = "kube-system"
  repository = "https://aws.github.io/eks-charts"
  chart      = "aws-load-balancer-controller"
  version    = var.lbc_chart_version

  wait    = true
  timeout = 600

  values = [yamlencode({
    clusterName  = var.cluster_name
    region       = var.region
    vpcId        = local.vpc_id
    replicaCount = var.lbc_replicas

    serviceAccount = {
      create = true
      name   = "aws-load-balancer-controller" # Pod Identity 연결과 같은 이름
    }

    # type: LoadBalancer Service를 가로채지 않는다(우리는 Ingress만 사용)
    enableServiceMutatorWebhook = false

    resources = {
      requests = { cpu = "50m", memory = "128Mi" }
      limits   = { memory = "256Mi" }
    }
  })]
}
