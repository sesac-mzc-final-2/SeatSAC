package com.seatsac.payment.service;

import com.seatsac.payment.entity.Payment;
import com.seatsac.payment.enums.PaymentCancelReason;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentMethod;
import com.seatsac.payment.enums.PaymentStatus;
import com.seatsac.payment.repository.PaymentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 트랜잭션 전용 — SQL만 한다. 모의 판정·지연은 이 클래스 밖(흐름 서비스)에서 한다.
 * 트랜잭션을 <b>짧게</b> 끊는 것이 목적이다: 지연 카드 5초 동안 커넥션을 쥐지 않는다(명세 4·7장).
 */
@Service
@RequiredArgsConstructor
public class PaymentStateService {

    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public Optional<Payment> find(String paymentId) {
        return paymentRepository.findById(paymentId);
    }

    /**
     * 2단계 — 판정 전 행을 만든다 (명세 4장).
     *
     * <p>「새로 만들었는지」를 함께 돌려주는 이유: 이미 있던 행이면 <b>판정을 다시 하지 않고</b>
     * 저장된 상태를 그대로 응답해야 한다({@code PROCESSING}이면 {@code PROCESSING}). 그러지 않으면
     * 지연 카드 재호출마다 5초를 또 기다리고, 재호출이 결과를 뒤집을 수도 있다.
     *
     * @throws DataIntegrityViolationException 같은 예매에 다른 결제가 이미 있을 때
     *                                        ({@code uk_payment_booking_id})
     */
    @Transactional
    public StartResult startOrFind(String paymentId, Long bookingId, Integer amount,
                                   PaymentMethod method, String cardLast4, Instant now) {
        return paymentRepository.findById(paymentId)
            .map(existing -> new StartResult(existing, false))
            .orElseGet(() -> new StartResult(paymentRepository.saveAndFlush(
                Payment.processing(paymentId, bookingId, amount, method, cardLast4, now)), true));
    }

    /** @param created 이번 요청이 행을 만들었는가 (false면 재호출이다) */
    public record StartResult(Payment payment, boolean created) {
    }

    /**
     * 4단계 — 판정 결과를 반영한다. {@code PROCESSING}일 때만 바뀌므로, 그 사이 다른 경로가
     * 닫았으면 0행이고 부르는 쪽이 현재 행을 다시 읽어 응답한다.
     */
    @Transactional
    public boolean settle(String paymentId, PaymentStatus status, PaymentFailureCode failureCode,
                          Instant now) {
        Instant approvedAt = status == PaymentStatus.APPROVED ? now : null;
        return paymentRepository.settleProcessing(paymentId, status, failureCode, approvedAt, now) == 1;
    }

    /** PAY-03 — 승인된 결제만 취소한다. 0행이면 이미 취소됐거나 승인 상태가 아니다 */
    @Transactional
    public boolean cancel(String paymentId, PaymentCancelReason reason, Instant now) {
        return paymentRepository.cancelApproved(paymentId, reason, now) == 1;
    }

    @Transactional(readOnly = true)
    public List<String> findStaleProcessingIds(Instant threshold, Pageable pageable) {
        return paymentRepository.findStaleProcessingIds(threshold, pageable);
    }
}
