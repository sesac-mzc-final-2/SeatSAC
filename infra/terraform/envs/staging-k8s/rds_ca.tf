# ------------------------------------------------------------------
# RDS CA 번들 → ConfigMap rds-ca-bundle (cinema-staging · cinema-ops)
#   DB에 접속하는 Job이 서버 인증서·호스트 이름을 검증하게 한다(CWE-295, 10/24 리뷰)
#     - db-bootstrap(cinema-ops)  : mysql --ssl-mode=VERIFY_IDENTITY --ssl-ca
#     - Flyway 훅 Job·시드 Job(cinema-staging) : FLYWAY_URL serverSslCert(MariaDB 드라이버 2.x)
#   공개 인증서라 비밀이 아니다. AWS가 번들을 갱신하면 다음 apply에서 ConfigMap이 바뀐다
#   (db-bootstrap은 해시가 바뀌어 다시 돌고, 결과는 같다 — 멱등)
# ------------------------------------------------------------------
data "http" "rds_ca" {
  url = "https://truststore.pki.rds.amazonaws.com/${var.region}/${var.region}-bundle.pem"

  lifecycle {
    postcondition {
      condition     = self.status_code == 200 && strcontains(self.response_body, "BEGIN CERTIFICATE")
      error_message = "RDS CA 번들을 받지 못했다 (truststore.pki.rds.amazonaws.com). 네트워크 확인 후 다시 plan"
    }
  }
}

resource "kubernetes_config_map_v1" "rds_ca" {
  for_each = toset([var.app_namespace, var.ops_namespace])

  metadata {
    name      = "rds-ca-bundle"
    namespace = kubernetes_namespace_v1.this[each.key].metadata[0].name
    labels = {
      "app.kubernetes.io/part-of"    = "cinema"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }

  data = {
    "rds-ca.pem" = data.http.rds_ca.response_body
  }
}
