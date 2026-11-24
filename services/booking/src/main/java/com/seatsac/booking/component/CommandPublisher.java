package com.seatsac.booking.component;

import com.seatsac.booking.entity.OutboxMessage;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * saga 명령 발송 — SQS {@code cinema-staging-payment-commands}(6.2.1, 설계 04). 본문은 outbox의 envelope 그대로,
 * 속성은 {@code type}·{@code traceId}·{@code correlationId}(이름 규칙 5장). {@link SqsNotificationPublisher}와 같은 방식이다:
 * 기본 변환기의 {@code JavaType} 속성을 끄고, SQS 자동 설정이 꺼진 테스트에서도 기동되게 처음 보낼 때 만든다.
 */
@Component
public class CommandPublisher {

    private final ObjectProvider<SqsAsyncClient> sqsClient;
    private final String paymentCommandsUrl;
    private volatile SqsTemplate template;

    public CommandPublisher(ObjectProvider<SqsAsyncClient> sqsClient,
                            @Value("${app.messaging.payment-commands-url:}") String paymentCommandsUrl) {
        this.sqsClient = sqsClient;
        this.paymentCommandsUrl = paymentCommandsUrl;
    }

    public void publish(OutboxMessage message) {
        if (paymentCommandsUrl.isBlank()) {
            throw new IllegalStateException("APP_MESSAGING_PAYMENT_COMMANDS_URL이 비어 있다");
        }
        template().send(to -> {
            to.queue(paymentCommandsUrl).payload(message.getPayload())
                .header("type", message.getEventType().messageType())
                .header("traceId", message.getTraceId());
            if (message.getCorrelationId() != null) {
                to.header("correlationId", message.getCorrelationId());
            }
        });
    }

    private SqsTemplate template() {
        SqsTemplate current = template;
        if (current != null) {
            return current;
        }
        SqsAsyncClient client = sqsClient.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("SQS 자동 설정이 꺼져 있다(spring.cloud.aws.sqs.enabled)");
        }
        current = SqsTemplate.builder()
            .sqsAsyncClient(client)
            .configureDefaultConverter(converter -> converter.doNotSendPayloadTypeHeader())
            .build();
        template = current;
        return current;
    }
}
