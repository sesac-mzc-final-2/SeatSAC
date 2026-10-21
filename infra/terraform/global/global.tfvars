# 비밀 없음 → 커밋한다. IAM 사용자 이름만 적고 이메일·비밀번호·키는 적지 않는다.
owner = "kimjongsik"

admin_users     = ["sesac-b2", "sesac-b3"]
developer_users = ["sesac-b1", "sesac-b2", "sesac-b3", "sesac-b5"]

attach_assume_policy_to_users = false

github_owner    = "sesac-mzc-final-2"
github_owner_id = "329788874"     # OIDC sub의 owner@ID (조직 설정: 변경 불가 ID 포함)
github_repo     = "SeatSAC"       # 대소문자 그대로
github_repo_id  = "1425477103"