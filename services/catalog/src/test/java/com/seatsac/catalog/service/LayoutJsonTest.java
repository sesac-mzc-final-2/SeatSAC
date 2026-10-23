package com.seatsac.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 시드 JSON(snake_case) → 계약 모양. 배열·숫자 좌표가 아니면 500(IllegalStateException)으로 막고 캐시에 넣지 않는다. */
class LayoutJsonTest {

    @Test
    void mapsSeedShapeAndDropsExtraKeys() {
        var entrances = LayoutJson.entrances("[{\"label\":\"입구\",\"x\":8.250,\"y\":0.082,\"angle_code\":20}]");
        assertThat(entrances).hasSize(1);
        assertThat(entrances.get(0).x()).isEqualByComparingTo(new BigDecimal("8.250"));
        assertThat(entrances.get(0).y()).isEqualByComparingTo(new BigDecimal("0.082"));

        var vertical = LayoutJson.verticalAisles("[{\"x_from\":1.799,\"x_to\":2.962,\"rows\":9},{\"x_from\":11.761,\"x_to\":12.923,\"rows\":3}]");
        assertThat(vertical).hasSize(2);
        assertThat(vertical.get(1).xTo()).isEqualByComparingTo(new BigDecimal("12.923"));

        var horizontal = LayoutJson.horizontalAisles("[{\"after_row\":\"E\",\"before_row\":\"F\",\"y_from\":4.809,\"y_to\":5.544}]");
        assertThat(horizontal).hasSize(1);
        assertThat(horizontal.get(0).yFrom()).isEqualByComparingTo(new BigDecimal("4.809"));
    }

    @Test
    void emptyArrayAndNullAreEmptyLists() {
        assertThat(LayoutJson.horizontalAisles("[]")).isEmpty();
        assertThat(LayoutJson.horizontalAisles(null)).isEmpty();
        assertThat(LayoutJson.entrances(" ")).isEmpty();
    }

    @Test
    void rejectsNonArrayNonObjectAndMissingCoordinates() {
        assertThatThrownBy(() -> LayoutJson.entrances("{\"x\":1,\"y\":2}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("배열");
        assertThatThrownBy(() -> LayoutJson.verticalAisles("[1,2]"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("객체");
        assertThatThrownBy(() -> LayoutJson.verticalAisles("[{\"x_from\":1.0}]"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("x_to");
        assertThatThrownBy(() -> LayoutJson.horizontalAisles("[{\"y_from\":\"a\",\"y_to\":2}]"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("y_from");
        assertThatThrownBy(() -> LayoutJson.entrances("not json"))
            .isInstanceOf(IllegalStateException.class);
    }
}
