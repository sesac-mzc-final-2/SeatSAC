package com.seatsac.booking.component;

import io.awspring.cloud.sqs.operations.SqsTemplate;
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
 * <p>{@code SqsTemplate}을 {@link ObjectProvider}로 받는 이유: 테스트 프로필은
 * {@code spring.cloud.aws.sqs.enabled=false}라 그 빈이 없다. 생성자에서 바로 받으면
 * <b>릴레이와 무관한 테스트까지 컨텍스트가 뜨지 않는다</b>. 없으면 보낼 때 실패하고,
 * 릴레이가 그 실패를 재시도로 처리한다.
 */
@Slf4j
@Component
public class SqsNotificationPublisher implements NotificationPublisher {

    private final ObjectProvider<SqsTemplate> sqsTemplate;
    private final String queueUrl;

    public SqsNotificationPublisher(ObjectProvider<SqsTemplate> sqsTemplate,
                                    @Value("${app.sqs.notification-queue-url:}") String queueUrl) {
        this.sqsTemplate = sqsTemplate;
        this.queueUrl = queueUrl;
    }

    @Override
    public void publish(String messageType, String traceId, String payload) {
        SqsTemplate template = sqsTemplate.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("SQS 자동 설정이 꺼져 있다(spring.cloud.aws.sqs.enabled)");
        }
        if (queueUrl.isBlank()) {
            throw new IllegalStateException("APP_SQS_NOTIFICATION_QUEUE_URL이 비어 있다");
        }
        template.send(to -> to.queue(queueUrl)
            .payload(payload)
            .header("type", messageType)
            .header("traceId", traceId));
    }
}
