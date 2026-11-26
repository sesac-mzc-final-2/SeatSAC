package com.seatsac.booking.component;

import com.seatsac.booking.entity.OutboxMessage;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * 6.2.4 예매 이벤트 발송 — SNS {@code cinema-staging-booking-events}(D28·D29). 받는 쪽은 user 구독 큐
 * {@code cinema-staging-user-booking-events}(raw message delivery)라 메시지 속성 {@code type}·{@code traceId}·
 * {@code correlationId}가 그대로 SQS 속성이 된다(이름 규칙 5장).
 *
 * <p>로컬은 SNS가 없어 구독 큐로 바로 보낸다({@code app.messaging.events-local-queue-url}, 런타임 계약 3장).
 * 둘 다 비어 있으면 {@link #enabled()}가 false라 아웃박스 행 자체를 만들지 않는다({@link BookingEvents}).
 * 클라이언트는 처음 보낼 때 만든다 — SNS·SQS 자동 설정이 꺼진 테스트에서도 기동되게({@link CommandPublisher}와 같은 방식).
 */
@Component
public class BookingEventPublisher {

    private final ObjectProvider<SnsClient> snsClient;
    private final ObjectProvider<SqsAsyncClient> sqsClient;
    private final String topicArn;
    private final String localQueueUrl;
    private volatile SqsTemplate localTemplate;

    public BookingEventPublisher(ObjectProvider<SnsClient> snsClient, ObjectProvider<SqsAsyncClient> sqsClient,
                                 @Value("${app.messaging.events-topic-arn:}") String topicArn,
                                 @Value("${app.messaging.events-local-queue-url:}") String localQueueUrl) {
        this.snsClient = snsClient;
        this.sqsClient = sqsClient;
        this.topicArn = topicArn;
        this.localQueueUrl = localQueueUrl;
    }

    /** 보낼 곳이 있는가 — 없으면 예매 이벤트 행을 만들지 않는다(토픽 전에 쌓이면 10회 재시도 끝에 FAILED) */
    public boolean enabled() {
        return !topicArn.isBlank() || !localQueueUrl.isBlank();
    }

    public void publish(OutboxMessage message) {
        if (!topicArn.isBlank()) {
            publishToSns(message);
        } else if (!localQueueUrl.isBlank()) {
            publishToLocalQueue(message);
        } else {
            throw new IllegalStateException("APP_MESSAGING_EVENTS_TOPIC_ARN이 비어 있다");
        }
    }

    private void publishToSns(OutboxMessage message) {
        SnsClient client = snsClient.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("SNS 자동 설정이 꺼져 있다(spring.cloud.aws.sns.enabled)");
        }
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put("type", text(message.getEventType().messageType()));
        attributes.put("traceId", text(message.getTraceId()));
        if (message.getCorrelationId() != null) {
            attributes.put("correlationId", text(message.getCorrelationId()));
        }
        client.publish(PublishRequest.builder().topicArn(topicArn).message(message.getPayload())
            .messageAttributes(attributes).build());
    }

    private void publishToLocalQueue(OutboxMessage message) {
        localTemplate().send(to -> {
            to.queue(localQueueUrl).payload(message.getPayload())
                .header("type", message.getEventType().messageType())
                .header("traceId", message.getTraceId());
            if (message.getCorrelationId() != null) {
                to.header("correlationId", message.getCorrelationId());
            }
        });
    }

    private SqsTemplate localTemplate() {
        SqsTemplate current = localTemplate;
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
        localTemplate = current;
        return current;
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
