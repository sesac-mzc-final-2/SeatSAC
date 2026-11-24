package com.seatsac.booking.checkout;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.annotation.SqsListenerAcknowledgementMode;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code booking-replies} 수신(D24) — 리스너 전용 스레드, 수동 확인(처리·커밋 뒤에만 삭제). 예외면 지우지 않아
 * 가시성 뒤 재배달, 3회 뒤 DLQ. 형식이 틀린 결과는 재배달해도 같아서 로그 후 지운다(설계 04 5장 — DLQ를 채우지 않는다).
 * {@code APP_MESSAGING_CONSUMER_ENABLED=false}(기본)면 빈이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.messaging", name = "consumer-enabled", havingValue = "true")
public class CheckoutReplyListener {

    private final CheckoutReplyHandler handler;

    @SqsListener(value = "${app.messaging.replies-url}",
        acknowledgementMode = SqsListenerAcknowledgementMode.MANUAL,
        maxConcurrentMessages = "${app.messaging.max-concurrent:10}",
        maxMessagesPerPoll = "${app.messaging.max-messages-per-poll:10}",
        pollTimeoutSeconds = "20")
    public void onMessage(String body, Acknowledgement acknowledgement) {
        try {
            handler.handle(body);
        } catch (MalformedReplyException e) {
            log.error("결과 형식 오류 — 지운다(재배달해도 같다): {}", e.getMessage());
        }
        acknowledgement.acknowledge();
    }
}
