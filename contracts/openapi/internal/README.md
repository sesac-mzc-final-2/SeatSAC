내부 API(`/internal/**`)는 Gateway가 라우팅하지 않는다. 서비스별로 `{service}.internal.openapi.yaml`. 외부 파일(`../{service}.openapi.yaml`)의 공통 스키마는 `$ref: '../x.openapi.yaml#/components/...'`로 재사용한다.
- catalog: `catalog.internal.openapi.yaml` — GET /internal/screenings/{id}, POST /internal/screenings/summaries
- user: `user.internal.openapi.yaml` — GET /internal/users/{userId}/preferences
- booking: GET /internal/bookings, GET /internal/screenings/seat-counts (300ms)
- payment: `payment.internal.openapi.yaml`
