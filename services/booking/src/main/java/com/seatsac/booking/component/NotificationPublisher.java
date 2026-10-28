package com.seatsac.booking.component;

/**
 * 예매 완료 알림 발행 포트 — 아웃박스 릴레이만 부른다(요청 스레드 금지, 런타임 계약 5장).
 *
 * <p>구현은 {@link SqsNotificationPublisher} 하나다. 포트로 둔 이유는 릴레이 테스트가
 * AWS 없이 돌 수 있어야 하고, 전송 실패를 일부러 만들어야 하기 때문이다.
 */
public interface NotificationPublisher {

    /**
     * 메시지를 보낸다. 성공하면 조용히 끝나고, <b>실패하면 예외를 던진다</b>
     * (릴레이가 재시도·한도 초과를 판단한다).
     *
     * @param messageType SQS 메시지 속성 {@code type} — {@code booking.confirmed}
     * @param traceId     SQS 메시지 속성 {@code traceId} — 소문자 16진수 32자리
     * @param payload     메시지 본문 JSON(envelope). 아웃박스에 저장된 값 그대로 보낸다
     */
    void publish(String messageType, String traceId, String payload);
}
