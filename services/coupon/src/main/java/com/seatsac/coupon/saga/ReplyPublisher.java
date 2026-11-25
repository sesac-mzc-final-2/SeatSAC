package com.seatsac.coupon.saga;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * 결과 발송 — SQS {@code cinema-staging-booking-replies}. 본문은 outbox의 envelope 그대로, 속성은
 * {@code type}·{@code traceId}·{@code correlationId}(이름 규칙 5장). booking {@code SqsNotificationPublisher}와 같은 방식:
 * 기본 변환기가 붙이는 {@code JavaType} 속성을 끄고, 자동 설정이 꺼진 테스트에서도 기동되게 처음 보낼 때 만든다.
 */
@Component
public class ReplyPublisher {

    private final ObjectProvider<SqsAsyncClient> sqsClient;
    private final String queueUrl;
    private volatile SqsTemplate template;

    public ReplyPublisher(ObjectProvider<SqsAsyncClient> sqsClient,
                          @Value("${app.messaging.replies-url:}") String queueUrl) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
    }

    public void publish(ReplyOutbox message) {
        if (queueUrl.isBlank()) {
            throw new IllegalStateException("APP_MESSAGING_REPLIES_URL이 비어 있다");
        }
        template().send(to -> {
            to.queue(queueUrl).payload(message.getPayload())
                .header("type", message.getEventType())
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
