package com.seatsac.catalog.event;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * 변경 이벤트 발송 — SNS {@code cinema-staging-catalog-events}(staging, {@code APP_MESSAGING_EVENTS_TOPIC_ARN}).
 * 본문은 outbox의 envelope 그대로, 메시지 속성은 {@code type}·{@code traceId}(이름 규칙 5장). 구독은 raw message delivery라
 * 속성이 SQS 메시지 속성으로 그대로 간다. catalog 이벤트는 saga가 아니라 {@code correlationId} 속성은 붙이지 않는다.
 *
 * <p>로컬은 ElasticMQ에 SNS가 없어 큐로 직접 보낸다({@code APP_MESSAGING_EVENTS_QUEUE_URL}, 런타임 계약 3장 「로컬」).
 * 주제가 있으면 주제가 먼저다. 둘 다 비었으면 예외 → 릴레이가 재시도·FAILED로 남긴다.
 * 클라이언트는 처음 보낼 때 꺼낸다 — 자동 설정이 꺼진 테스트에서도 기동되게(coupon {@code ReplyPublisher}와 같은 방식).
 */
@Component
public class CatalogEventPublisher {

    private final ObjectProvider<SnsClient> snsClient;
    private final ObjectProvider<SqsAsyncClient> sqsClient;
    private final String topicArn;
    private final String queueUrl;

    public CatalogEventPublisher(ObjectProvider<SnsClient> snsClient, ObjectProvider<SqsAsyncClient> sqsClient,
                                 @Value("${app.messaging.events-topic-arn:}") String topicArn,
                                 @Value("${app.messaging.events-queue-url:}") String queueUrl) {
        this.snsClient = snsClient;
        this.sqsClient = sqsClient;
        this.topicArn = topicArn;
        this.queueUrl = queueUrl;
    }

    public void publish(CatalogOutbox message) {
        if (!topicArn.isBlank()) {
            publishToTopic(message);
        } else if (!queueUrl.isBlank()) {
            publishToQueue(message);
        } else {
            throw new IllegalStateException("APP_MESSAGING_EVENTS_TOPIC_ARN이 비어 있다");
        }
    }

    private void publishToTopic(CatalogOutbox message) {
        SnsClient client = snsClient.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("SNS 자동 설정이 꺼져 있다(spring.cloud.aws.sns.enabled)");
        }
        client.publish(PublishRequest.builder()
            .topicArn(topicArn)
            .message(message.getPayload())
            .messageAttributes(Map.of(
                "type", snsAttribute(message.getEventType()),
                "traceId", snsAttribute(message.getTraceId())))
            .build());
    }

    private void publishToQueue(CatalogOutbox message) {
        SqsAsyncClient client = sqsClient.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("SQS 자동 설정이 꺼져 있다(spring.cloud.aws.sqs.enabled)");
        }
        client.sendMessage(SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(message.getPayload())
            .messageAttributes(Map.of(
                "type", sqsAttribute(message.getEventType()),
                "traceId", sqsAttribute(message.getTraceId())))
            .build()).join();
    }

    private static software.amazon.awssdk.services.sns.model.MessageAttributeValue snsAttribute(String value) {
        return software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
            .dataType("String").stringValue(value).build();
    }

    private static software.amazon.awssdk.services.sqs.model.MessageAttributeValue sqsAttribute(String value) {
        return software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
            .dataType("String").stringValue(value).build();
    }
}
