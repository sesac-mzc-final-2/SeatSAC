package com.seatsac.catalog.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * 6.5.1c 통합 테스트 — catalog 이벤트 <b>발행 확인</b>. {@link CatalogChangeEventsIntegrationTest}는 발송기를 목으로 두고
 * outbox 행까지 본다. 여기서는 발송기를 진짜로 두고 ElasticMQ(로컬과 같은 이미지·버전) 큐에서 메시지를 <b>받아</b> 본다 —
 * ai 복제본(`ai-catalog-events`)이 받는 것과 같은 모양인지: 본문 = 계약 envelope, 메시지 속성 {@code type}·{@code traceId}가
 * 본문과 같다, 바뀐 것만 다음 {@code version}으로 한 번 더 온다, 지우면 {@code deleted: true}로 온다.
 *
 * <p>staging은 SNS 주제 → 구독 큐(raw message delivery)라 속성이 그대로 간다. 로컬·이 테스트는 SNS가 없어 큐로 직접
 * 보낸다({@code APP_MESSAGING_EVENTS_QUEUE_URL}, 런타임 계약 3장) — 본문·속성 모양은 같다.
 */
@SpringBootTest(properties = {
    "spring.cloud.aws.sqs.enabled=true",
    "app.messaging.events-topic-arn="
})
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class})
class CatalogEventQueueIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path CONTRACTS = Path.of("../../contracts/events");
    static final String QUEUE = "cinema-staging-ai-catalog-events";

    static final GenericContainer<?> ELASTICMQ =
        new GenericContainer<>(DockerImageName.parse("softwaremill/elasticmq-native:1.7.1"))   // compose와 같은 태그
            .withExposedPorts(9324)
            .waitingFor(Wait.forListeningPort());

    static SqsClient sqs;
    static String queueUrl;

    static {
        ELASTICMQ.start();
        String endpoint = "http://" + ELASTICMQ.getHost() + ":" + ELASTICMQ.getMappedPort(9324);
        sqs = SqsClient.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build();
        queueUrl = sqs.createQueue(b -> b.queueName(QUEUE)).queueUrl();
    }

    @DynamicPropertySource
    static void messaging(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.sqs.endpoint",
            () -> "http://" + ELASTICMQ.getHost() + ":" + ELASTICMQ.getMappedPort(9324));
        registry.add("app.messaging.events-queue-url", () -> queueUrl);
    }

    @AfterAll
    static void stop() {
        sqs.close();
        ELASTICMQ.stop();
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CatalogChangeScanner scanner;
    @Autowired
    CatalogOutboxRelay relay;
    @Autowired
    CatalogOutboxRepository outboxRepository;

    @BeforeEach
    void fixture() {
        drain();
        jdbc.update("DELETE FROM outbox_message");
        jdbc.update("DELETE FROM change_ledger");
        jdbc.update("DELETE FROM screening");
        jdbc.update("DELETE FROM seat");
        jdbc.update("DELETE FROM auditorium_layout");
        jdbc.update("DELETE FROM auditorium");
        jdbc.update("DELETE FROM theater");
        jdbc.update("DELETE FROM movie");

        jdbc.update("INSERT INTO theater (theater_id, name, source_provider, source_theater_id, region, has_layout) "
            + "VALUES (1, '테스트극장', 'TEST', 1, '서울', 1)");
        jdbc.update("INSERT INTO auditorium (auditorium_id, theater_id, name, screen_type_code, source_auditorium_id, "
            + "total_seats, layout_version) VALUES (10, 1, '1관', 986, 'A', 4, 1)");
        jdbc.update("INSERT INTO auditorium_layout (auditorium_id, layout_version, screen_x_from, screen_x_to, entrances, "
            + "vertical_aisles, horizontal_aisles, source_provider, fetched_at) VALUES (10, 1, 0, 9.5, '[]', '[]', '[]', "
            + "'TEST', '2026-10-14 00:45:47.000')");
        jdbc.update("INSERT INTO movie (movie_id, source_provider, source_movie_code, title, title_en, view_grade, "
            + "view_grade_code, slot_minutes) VALUES (1, 'TEST', 'M1', '첫 영화', 'First', '12', 12, 115)");
        jdbc.update("INSERT INTO screening (screening_id, auditorium_id, layout_version, movie_id, business_date, play_sequence, "
            + "starts_at, ends_at, raw_start_time, raw_end_time, film_format) VALUES "
            + "(5001, 10, 1, 1, '2026-11-16', 2, '2026-11-16 05:30:00.000', '2026-11-16 07:25:00.000', '14:30', '16:25', '2D'), "
            + "(5002, 10, 1, 1, '2026-11-16', 3, '2026-11-16 08:00:00.000', '2026-11-16 09:55:00.000', '17:00', '18:55', '2D')");
    }

    @Test
    void relayedEventsArriveInTheQueueInContractShapeWithMatchingAttributes() throws Exception {
        scanner.scanOnce();
        assertThat(relay.relayOnce()).isEqualTo(3);
        assertThat(outboxRepository.countUnsent()).isZero();

        List<Message> received = receive(3);
        assertThat(received).hasSize(3);

        Set<String> eventIds = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (Message message : received) {
            JsonNode body = JSON.readTree(message.body());
            String type = body.path("type").asText();
            // 소비자(ai·booking)는 속성으로 고르고 본문으로 처리한다 — 둘이 같아야 한다
            assertThat(attribute(message, "type")).isEqualTo(type);
            assertThat(attribute(message, "traceId")).isEqualTo(body.path("traceId").asText()).matches("^[0-9a-f]{32}$");
            assertThat(message.messageAttributes()).doesNotContainKey("correlationId");   // saga가 아니다
            assertRequiredKeys(body, type + ".v1.json");
            assertThat(body.path("producer").asText()).isEqualTo("catalog");
            assertThat(body.path("payload").path("version").asLong()).isEqualTo(1);
            eventIds.add(body.path("eventId").asText());
            seen.add(type + ":" + id(body));
        }
        assertThat(eventIds).hasSize(3);
        assertThat(seen).containsExactlyInAnyOrder("screening.changed:5001", "screening.changed:5002", "movie.changed:1");

        // 바뀐 것이 없으면 다음 주기에 큐로 아무것도 가지 않는다(복제본이 같은 내용을 또 받지 않음)
        scanner.scanOnce();
        assertThat(relay.relayOnce()).isZero();
        assertThat(receive(1)).isEmpty();
    }

    @Test
    void onlyTheChangedRowArrivesAgainWithTheNextVersionAndDeletionArrivesAsDeleted() throws Exception {
        scanner.scanOnce();
        relay.relayOnce();
        assertThat(receive(3)).hasSize(3);

        // 시드·TMDB 스크립트처럼 SQL로 바꾼다(catalog에는 쓰기 API가 없다)
        jdbc.update("UPDATE movie SET title = '첫 영화 (재개봉)' WHERE movie_id = 1");
        jdbc.update("DELETE FROM screening WHERE screening_id = 5002");
        scanner.scanOnce();
        assertThat(relay.relayOnce()).isEqualTo(2);

        List<Message> received = receive(2);
        assertThat(received).hasSize(2);
        JsonNode movie = null;
        JsonNode screening = null;
        for (Message message : received) {
            JsonNode body = JSON.readTree(message.body());
            assertThat(attribute(message, "type")).isEqualTo(body.path("type").asText());
            if ("movie.changed".equals(body.path("type").asText())) {
                movie = body;
            } else {
                screening = body;
            }
        }
        assertThat(movie).isNotNull();
        assertRequiredKeys(movie, "movie.changed.v1.json");
        assertThat(movie.path("payload").path("movieId").asLong()).isEqualTo(1);
        assertThat(movie.path("payload").path("version").asLong()).isEqualTo(2);
        assertThat(movie.path("payload").path("title").asText()).isEqualTo("첫 영화 (재개봉)");

        assertThat(screening).isNotNull();
        assertRequiredKeys(screening, "screening.changed.v1.json");
        assertThat(screening.path("payload").path("screeningId").asLong()).isEqualTo(5002);
        assertThat(screening.path("payload").path("version").asLong()).isEqualTo(2);
        assertThat(screening.path("payload").path("deleted").asBoolean()).isTrue();
    }

    // --- helpers ---

    /** 최대 {@code expected}개를 기다려 받고 지운다. 0개를 기대하면 짧게 한 번 본다 */
    private static List<Message> receive(int expected) {
        List<Message> all = new ArrayList<>();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(expected == 1 ? 2 : 10));
        while (all.size() < expected && Instant.now().isBefore(deadline)) {
            List<Message> batch = sqs.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1)
                .messageAttributeNames("All")).messages();
            for (Message message : batch) {
                sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
            }
            all.addAll(batch);
        }
        return all;
    }

    private static void drain() {
        sqs.purgeQueue(b -> b.queueUrl(queueUrl));
    }

    private static String attribute(Message message, String name) {
        MessageAttributeValue value = message.messageAttributes().get(name);
        assertThat(value).as("메시지 속성 " + name).isNotNull();
        assertThat(value.dataType()).isEqualTo("String");
        return value.stringValue();
    }

    private static long id(JsonNode body) {
        JsonNode payload = body.path("payload");
        return payload.has("screeningId") ? payload.path("screeningId").asLong() : payload.path("movieId").asLong();
    }

    /** 계약의 envelope required + payload required 키가 본문에 있는지 (모양 전체는 CatalogChangeEventsIntegrationTest가 본다) */
    private static void assertRequiredKeys(JsonNode message, String schemaFile) throws Exception {
        JsonNode schema = JSON.readTree(Files.readString(CONTRACTS.resolve(schemaFile)));
        schema.path("required").forEach(key -> assertThat(message.has(key.asText())).as(key.asText()).isTrue());
        JsonNode payload = message.path("payload");
        schema.path("properties").path("payload").path("required")
            .forEach(key -> assertThat(payload.has(key.asText())).as("payload." + key.asText()).isTrue());
    }
}
