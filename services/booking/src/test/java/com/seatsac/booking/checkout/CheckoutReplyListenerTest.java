package com.seatsac.booking.checkout;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import org.junit.jupiter.api.Test;

/** 리스너는 형식 오류만 지우고, 처리 중 난 다른 예외는 지우지 않아 재배달시킨다(04 5장, #183 리뷰) */
class CheckoutReplyListenerTest {

    private final CheckoutReplyHandler handler = mock(CheckoutReplyHandler.class);
    private final Acknowledgement ack = mock(Acknowledgement.class);
    private final CheckoutReplyListener listener = new CheckoutReplyListener(handler);

    @Test
    void malformedReplyIsAcknowledged() {
        doThrow(new MalformedReplyException("bad")).when(handler).handle("x");

        listener.onMessage("x", ack);

        verify(ack).acknowledge();
    }

    @Test
    void otherIllegalArgumentIsNotAcknowledged() {
        doThrow(new IllegalArgumentException("processing")).when(handler).handle("x");

        assertThatThrownBy(() -> listener.onMessage("x", ack)).isInstanceOf(IllegalArgumentException.class);
        verify(ack, never()).acknowledge();
    }
}
