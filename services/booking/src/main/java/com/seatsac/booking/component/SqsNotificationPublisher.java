package com.seatsac.booking.component;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SQS 발행 — 알림 큐 {@code cinema-staging-notification-events}(이름 규칙 5장).
 * 본문은 아웃박스에 저장된 envelope 그대로, 속성은 {@code type}·{@code traceId} 둘뿐이다.
 *
 * <p>자격 증명은 Pod Identity로 받는다(액세스 키 없음). 로컬은 ElasticMQ.
 *
 * <p>{@code SqsTemplate}을 자동 설정 빈이 아니라 <b>여기서 만든다</b> — 기본 변환기가 payload
 * 타입을 {@code JavaType} 메시지 속성으로 덧붙이는데, 계약(이름 규약 5장)이 정한 속성은
 * {@code type}·{@code traceId} 둘뿐이다. 로컬 ElasticMQ로 실제 보내 보고 확인했다(10/26).
 *
 * <p>{@link SqsAsyncClient}를 {@link ObjectProvider}로 받는 이유: 테스트 프로필은
 * {@code spring.cloud.aws.sqs.enabled=false}라 그 빈이 없다. 생성자에서 바로 받으면
 * <b>릴레이와 무관한 테스트까지 컨텍스트가 뜨지 않는다</b>. 없으면 보낼 때 실패하고,
 * 릴레이가 그 실패를 재시도로 처리한다.
 */
@Slf4j
@Component
public class SqsNotificationPublisher implements NotificationPublisher {

    private final ObjectProvider<SqsAsyncClient> sqsClient;
    private final String queueUrl;
    private volatile SqsTemplate template;

    public SqsNotificationPublisher(ObjectProvider<SqsAsyncClient> sqsClient,
                                    @Value("${app.sqs.notification-queue-url:}") String queueUrl) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
    }

    /**
     * 처음 보낼 때 만든다 — 자동 설정이 꺼져 있어도 기동은 되어야 한다.
     * 기본 변환기는 payload 타입을 {@code JavaType} 속성으로 덧붙이는데, 계약이 정한 속성은
     * {@code type}·{@code traceId} 둘뿐이라 끈다.
     */
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

    @Override
    public void publish(String messageType, String traceId, String payload) {
        if (queueUrl.isBlank()) {
            throw new IllegalStateException("APP_SQS_NOTIFICATION_QUEUE_URL이 비어 있다");
        }
        template().send(to -> to.queue(queueUrl)
            .payload(payload)
            .header("type", messageType)
            .header("traceId", traceId));
    }
}
