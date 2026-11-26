package com.seatsac.user.history;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.annotation.SqsListenerAcknowledgementMode;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code cinema-staging-user-booking-events} 수신(SNS {@code booking-events} raw message delivery — 본문이 곧 envelope).
 * coupon {@code CouponCommandListener}와 같은 방식: <b>수동 확인</b> — 반영·커밋이 끝난 뒤에만 지운다. 예외면 지우지 않아
 * 가시성 시간 뒤 재배달, 3회 뒤 DLQ. 형식이 틀린 이벤트만 로그 + 지표를 남기고 지운다.
 * {@code APP_MESSAGING_CONSUMER_ENABLED=false}(기본)면 빈이 없다 — Pod Identity·ServiceAccount 전에 기동이 실패하지 않게.
 */
@Component
@ConditionalOnProperty(prefix = "app.messaging", name = "consumer-enabled", havingValue = "true")
public class BookingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);

    private final BookingEventHandler handler;

    public BookingEventListener(BookingEventHandler handler) {
        this.handler = handler;
    }

    @SqsListener(value = "${app.messaging.booking-events-url}",
        acknowledgementMode = SqsListenerAcknowledgementMode.MANUAL,
        maxConcurrentMessages = "${app.messaging.max-concurrent:10}",
        maxMessagesPerPoll = "${app.messaging.max-messages-per-poll:10}",
        pollTimeoutSeconds = "20")
    public void onMessage(String body, Acknowledgement acknowledgement) {
        try {
            handler.handle(body);
        } catch (MalformedEventException e) {
            log.error("이벤트 형식 오류 — 지운다: {}", e.getMessage());
        }
        acknowledgement.acknowledge();
    }
}
