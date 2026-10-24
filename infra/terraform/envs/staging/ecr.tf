# ------------------------------------------------------------------
# 3.1.4 ECR — 서비스 이미지 저장소 cinema/{service} (frontend 없음, docs/conventions.md 9장)
#   - 태그 = git SHA 7자리, IMMUTABLE (같은 태그를 다른 이미지로 덮어쓸 수 없음 → 롤백 = 이전 태그)
#   - push는 CI 역할 cinema-gha-ecr-push만 (저장소 정책 Deny). 사람·관리자도 push 불가
#     (팀원은 global team_roles.tf DenyImagePush로 한 번 더 막혀 있음)
#   - pull은 같은 계정 노드 역할(EKS 모듈 기본 ECR 읽기 정책)이라 저장소 정책이 필요 없다
#   - force_delete: staging 재생성 리허설(런북 11장)에서 destroy가 이미지 때문에 멈추지 않게.
#     지운 뒤에는 build-push 수동 실행(services=all)으로 다시 올린다
# ------------------------------------------------------------------

locals {
  ecr_services = toset(["gateway", "catalog", "booking", "user", "payment"])

  # 사람이 직접 올리는 것을 막을 push 계열 API
  ecr_push_actions = [
    "ecr:PutImage",
    "ecr:InitiateLayerUpload",
    "ecr:UploadLayerPart",
    "ecr:CompleteLayerUpload",
  ]
}

resource "aws_ecr_repository" "service" {
  for_each = local.ecr_services

  name                 = "cinema/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true # 기본 스캔(무료). CI의 Trivy와 별개로 콘솔·CLI에서 결과 확인
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# 최근 30개만 남김 (롤백은 직전 몇 개면 충분, 저장 비용 억제). 태그 없는 레이어는 1일 뒤 삭제
resource "aws_ecr_lifecycle_policy" "service" {
  for_each   = aws_ecr_repository.service
  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "untagged 1 day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "keep last 30 images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 30
        }
        action = { type = "expire" }
      },
    ]
  })
}

data "aws_iam_policy_document" "ecr_push_ci_only" {
  statement {
    sid     = "DenyPushExceptCi"
    effect  = "Deny"
    actions = local.ecr_push_actions

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    # 역할 세션(assumed-role)도 aws:PrincipalArn은 역할 ARN으로 평가된다
    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = [local.gha_ecr_push_arn]
    }
  }
}

resource "aws_ecr_repository_policy" "service" {
  for_each   = aws_ecr_repository.service
  repository = each.value.name
  policy     = data.aws_iam_policy_document.ecr_push_ci_only.json
}

output "ecr_repository_urls" {
  description = "서비스별 ECR 주소 (build-push가 레지스트리 주소를 직접 구하므로 GitHub에 넣을 값은 없음)"
  value       = { for k, r in aws_ecr_repository.service : k => r.repository_url }
}
