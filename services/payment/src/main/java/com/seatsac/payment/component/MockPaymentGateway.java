package com.seatsac.payment.component;

import com.seatsac.payment.common.PaymentPolicy;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentStatus;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 모의 승인 — 명세 5장 (ADR-13). 외부 PG를 부르지 않고 <b>카드 끝 4자리</b>로 결과를 정한다.
 *
 * <table>
 *   <tr><td>{@code 0000}</td><td>거절 {@code CARD_DECLINED}</td></tr>
 *   <tr><td>{@code 1111}</td><td>거절 {@code INSUFFICIENT_BALANCE}</td></tr>
 *   <tr><td>{@code 9999}</td><td>5초 지연 후 승인 — Booking 타임아웃 → {@code PROCESSING} 시연</td></tr>
 *   <tr><td>그 외</td><td>승인</td></tr>
 * </table>
 *
 * <p>지연 카드와 지연 시간은 설정({@code APP_PAYMENT_MOCK_DELAY_CARD}·{@code APP_PAYMENT_MOCK_DELAY})에서
 * 읽는다 — 시연 중에 값을 바꿀 수 있어야 한다. 거절 카드 두 개는 상수로 둔다.
 *
 * <p><b>트랜잭션 밖에서만</b> 부른다. 지연 중 DB 커넥션을 쥐면 Hikari 5개가 금방 마른다(명세 7장).
 */
@Slf4j
@Component
public class MockPaymentGateway {

    private final Duration delay;
    private final String delayedCard;

    public MockPaymentGateway(@Value("${app.payment.mock-delay}") Duration delay,
                              @Value("${app.payment.mock-delay-card}") String delayedCard) {
        this.delay = delay;
        this.delayedCard = delayedCard;
    }

    /** 판정 결과 — 승인이면 {@code failureCode}가 없다 */
    public record Judgement(PaymentStatus status, PaymentFailureCode failureCode) {

        private static Judgement approved() {
            return new Judgement(PaymentStatus.APPROVED, null);
        }

        private static Judgement declined(PaymentFailureCode failureCode) {
            return new Judgement(PaymentStatus.FAILED, failureCode);
        }
    }

    public Judgement judge(String cardLast4) {
        if (PaymentPolicy.DECLINED_CARD.equals(cardLast4)) {
            return Judgement.declined(PaymentFailureCode.CARD_DECLINED);
        }
        if (PaymentPolicy.INSUFFICIENT_CARD.equals(cardLast4)) {
            return Judgement.declined(PaymentFailureCode.INSUFFICIENT_BALANCE);
        }
        if (delayedCard.equals(cardLast4)) {
            sleepForDemo();
        }
        return Judgement.approved();
    }

    /** 지연 시연용. 인터럽트가 오면(종료) 기다리지 않고 바로 승인으로 넘어간다 */
    private void sleepForDemo() {
        try {
            log.info("지연 카드 — {}ms 뒤 승인한다", delay.toMillis());
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("지연 대기가 중단됐다 — 승인으로 넘어간다");
        }
    }
}
