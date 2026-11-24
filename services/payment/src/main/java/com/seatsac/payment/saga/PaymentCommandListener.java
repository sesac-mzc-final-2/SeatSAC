package com.seatsac.payment.saga;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.annotation.SqsListenerAcknowledgementMode;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code payment-commands} 수신(D24) — Spring Cloud AWS 리스너 전용 스레드(롱폴링 20초). 기존 {@code @Scheduled} 풀은 쓰지 않는다.
 *
 * <p><b>수동 확인</b>: 처리·커밋이 끝난 뒤에만 지운다. 예외면 지우지 않아 가시성(30초) 뒤 재배달, 3회 뒤 DLQ.
 * 형식이 틀린 명령만 로그 + 지표를 남기고 지운다(설계 04 5장 — 재배달해도 같아 DLQ를 채우지 않는다).
 * {@code APP_MESSAGING_CONSUMER_ENABLED=false}(기본)면 빈이 없다 — 큐·Pod Identity(6.1.3) 전에 기동이 실패하지 않게.
 * 동시 처리 수는 {@code APP_MESSAGING_MAX_CONCURRENT}(비교 측정 200 → 100·50 추가 측정, 05 2-1). 한 번에 받는 수는
 * 그보다 클 수 없어서 따로 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.messaging", name = "consumer-enabled", havingValue = "true")
public class PaymentCommandListener {

    private final PaymentCommandHandler handler;

    @SqsListener(value = "${app.messaging.commands-url}",
        acknowledgementMode = SqsListenerAcknowledgementMode.MANUAL,
        maxConcurrentMessages = "${app.messaging.max-concurrent:10}",
        maxMessagesPerPoll = "${app.messaging.max-messages-per-poll:10}",
        pollTimeoutSeconds = "20")
    public void onMessage(String body, Acknowledgement acknowledgement) {
        try {
            handler.handle(body);
        } catch (MalformedCommandException e) {
            // 재배달해도 같다 — 지운다(지표는 handler가 셌다). 본문 원문은 남기지 않는다(카드 끝 4자리 등)
            log.error("명령 형식 오류 — 지운다: {}", e.getMessage());
        }
        acknowledgement.acknowledge();
    }
}
