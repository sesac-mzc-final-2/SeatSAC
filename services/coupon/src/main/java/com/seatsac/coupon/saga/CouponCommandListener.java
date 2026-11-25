package com.seatsac.coupon.saga;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.annotation.SqsListenerAcknowledgementMode;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code coupon-commands} 수신 — payment {@code PaymentCommandListener}와 같은 방식. <b>수동 확인</b>: 처리·커밋이 끝난 뒤에만
 * 지운다. 예외면 지우지 않아 가시성(30초) 뒤 재배달, 3회 뒤 DLQ. 형식이 틀린 명령만 로그 + 지표를 남기고 지운다.
 * {@code APP_MESSAGING_CONSUMER_ENABLED=false}(기본)면 빈이 없다 — 큐·Pod Identity 전에 기동이 실패하지 않게.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.messaging", name = "consumer-enabled", havingValue = "true")
public class CouponCommandListener {

    private final CouponCommandHandler handler;

    @SqsListener(value = "${app.messaging.commands-url}",
        acknowledgementMode = SqsListenerAcknowledgementMode.MANUAL,
        maxConcurrentMessages = "${app.messaging.max-concurrent:10}",
        maxMessagesPerPoll = "${app.messaging.max-messages-per-poll:10}",
        pollTimeoutSeconds = "20")
    public void onMessage(String body, Acknowledgement acknowledgement) {
        try {
            handler.handle(body);
        } catch (MalformedCommandException e) {
            log.error("명령 형식 오류 — 지운다: {}", e.getMessage());
        }
        acknowledgement.acknowledge();
    }
}
