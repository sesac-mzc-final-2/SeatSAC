내부 API(`/internal/**`)는 Gateway가 라우팅하지 않는다. 서비스별로 `{service}.internal.openapi.yaml`.
- catalog: GET /internal/screenings/{id}, POST /internal/screenings/summaries, GET /internal/theaters
- booking: GET /internal/bookings, GET /internal/screenings/seat-counts (300ms)
- payment: 위 파일
