package com.seatsac.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse.Entrance;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse.HorizontalAisle;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse.VerticalAisle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * {@code auditorium_layout}의 JSON 컬럼(lotte-seed 모양, snake_case) → 계약 모양(camelCase).
 * 계약에 없는 키(label·angle_code·rows·after_row 등)는 버린다.
 *
 * <p>DDL의 {@code JSON NOT NULL}은 배열 모양이나 좌표 필드를 보장하지 않으므로 여기서 확인한다.
 * 배열이 아니거나 좌표가 숫자가 아니면 {@link IllegalStateException}(→ 500) — 저장 데이터 오류라 캐시에 넣지 않고
 * 잘못된 배치도를 내보내지 않는다. 메시지에 저장 값은 담지 않는다.
 */
final class LayoutJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LayoutJson() {
    }

    static List<Entrance> entrances(String json) {
        return elements("entrances", json, n -> new Entrance(decimal(n, "x"), decimal(n, "y")));
    }

    static List<VerticalAisle> verticalAisles(String json) {
        return elements("vertical_aisles", json, n -> new VerticalAisle(decimal(n, "x_from"), decimal(n, "x_to")));
    }

    static List<HorizontalAisle> horizontalAisles(String json) {
        return elements("horizontal_aisles", json, n -> new HorizontalAisle(decimal(n, "y_from"), decimal(n, "y_to")));
    }

    private static <T> List<T> elements(String column, String json, Function<JsonNode, T> toElement) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode array;
        try {
            array = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("auditorium_layout." + column + " JSON을 읽을 수 없습니다", e);
        }
        if (!array.isArray()) {
            throw new IllegalStateException("auditorium_layout." + column + " JSON이 배열이 아닙니다");
        }
        List<T> out = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            if (!node.isObject()) {
                throw new IllegalStateException("auditorium_layout." + column + " 원소가 객체가 아닙니다");
            }
            out.add(toElement.apply(node));
        }
        return out;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException("auditorium_layout 좌표 " + field + " 가 없거나 숫자가 아닙니다");
        }
        return value.decimalValue();
    }
}
