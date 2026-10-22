package com.seatsac.booking.controller;

import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.idempotency.Idempotent;
import com.seatsac.booking.dto.response.SeatHoldResponse;
import com.seatsac.booking.dto.response.SeatStatusResponse;
import com.seatsac.booking.service.SeatHoldService;
import com.seatsac.booking.service.SeatSnapshotService;
import com.seatsac.booking.service.SeatStreamService;
import com.seatsac.common.core.http.Headers;
import com.seatsac.common.web.user.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 좌석 API — `contracts/openapi/booking.openapi.yaml`.
 * 경로는 Gateway가 {@code /api}를 떼고 넘긴 서비스 경로다.
 */
@RestController
@RequiredArgsConstructor
public class SeatController {

    private final SeatSnapshotService seatSnapshotService;
    private final SeatHoldService seatHoldService;
    private final SeatStreamService seatStreamService;

    /** BKG-01 좌석 현황 조회 (인증 불필요) */
    @GetMapping("/screenings/{screeningId}/seats")
    public SeatStatusResponse seats(@PathVariable Long screeningId) {
        return seatSnapshotService.snapshot(screeningId);
    }

    /**
     * BKG-05 실시간 좌석 이벤트 (SSE, 인증 불필요). {@code Cache-Control: no-cache}가 꼭 필요하다 —
     * CloudFront·ALB 경유에서 캐시되면 화면이 과거 이벤트를 받는다(명세 6장 SSE 세트 ④).
     *
     * <p>{@code Last-Event-ID}는 받아 두지만 복구에 쓰지 않는다. 빈틈은 BKG-01 스냅샷으로
     * 다시 맞춘다(Pub/Sub은 지나간 이벤트를 다시 주지 않는다).
     */
    @GetMapping(value = "/screenings/{screeningId}/seats/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable Long screeningId,
                                             @RequestHeader(value = "Last-Event-ID", required = false)
                                             String lastEventId) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .body(seatStreamService.subscribe(screeningId));
    }

    /**
     * BKG-02 좌석 선점. {@code Idempotency-Key} 필수 — 헤더가 없으면 공통 처리가
     * 400 {@code VALIDATION_FAILED}로 돌려준다. 키를 실제로 쓰는 멱등 처리는 3.2.4.
     */
    @Idempotent
    @PostMapping("/seats/hold")
    @ResponseStatus(HttpStatus.CREATED)
    public SeatHoldResponse hold(@CurrentUser String userId,
                                 @RequestHeader(Headers.IDEMPOTENCY_KEY) String idempotencyKey,
                                 @Valid @RequestBody SeatHoldRequest request) {
        return seatHoldService.hold(userId, request);
    }
}
