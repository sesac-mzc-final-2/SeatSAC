package com.seatsac.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.catalog.cache.CacheKeys;
import com.seatsac.catalog.cache.CatalogCache;
import com.seatsac.catalog.common.CatalogErrorCode;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse.Entrance;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse.HorizontalAisle;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse.LayoutSeat;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse.Screen;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse.Source;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse.VerticalAisle;
import com.seatsac.catalog.dto.ScreenType;
import com.seatsac.catalog.dto.SeatType;
import com.seatsac.catalog.entity.Auditorium;
import com.seatsac.catalog.entity.AuditoriumLayout;
import com.seatsac.catalog.entity.AuditoriumLayout.LayoutId;
import com.seatsac.catalog.repository.AuditoriumLayoutRepository;
import com.seatsac.catalog.repository.AuditoriumRepository;
import com.seatsac.catalog.repository.SeatRepository;
import com.seatsac.common.core.time.Times;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * CAT-04 배치도. 캐시 키 {@code catalog:layout:{auditoriumId}:{version}}. booking도 이 응답을 (상영관, 버전)으로 파드 캐시한다.
 * {@code version} 생략 시 상영관의 현재 버전({@code auditorium.layout_version})을 쓴다.
 */
@Service
public class LayoutService {

    private final AuditoriumRepository auditoriumRepository;
    private final AuditoriumLayoutRepository layoutRepository;
    private final SeatRepository seatRepository;
    private final CatalogCache cache;
    private final ObjectMapper mapper;

    public LayoutService(AuditoriumRepository auditoriumRepository, AuditoriumLayoutRepository layoutRepository,
                         SeatRepository seatRepository, CatalogCache cache, ObjectMapper mapper) {
        this.auditoriumRepository = auditoriumRepository;
        this.layoutRepository = layoutRepository;
        this.seatRepository = seatRepository;
        this.cache = cache;
        this.mapper = mapper;
    }

    public AuditoriumLayoutResponse get(Long auditoriumId, Integer version) {
        int resolved = version != null ? version : auditorium(auditoriumId).getLayoutVersion();
        return cache.getOrLoad(CacheKeys.layout(auditoriumId, resolved), AuditoriumLayoutResponse.class,
            () -> load(auditoriumId, resolved));
    }

    private Auditorium auditorium(Long auditoriumId) {
        return auditoriumRepository.findById(auditoriumId).orElseThrow(CatalogErrorCode.AUDITORIUM_NOT_FOUND::exception);
    }

    private AuditoriumLayoutResponse load(Long auditoriumId, int version) {
        Auditorium auditorium = auditorium(auditoriumId);
        AuditoriumLayout layout = layoutRepository.findById(new LayoutId(auditoriumId, version))
            .orElseThrow(CatalogErrorCode.LAYOUT_NOT_FOUND::exception);
        List<LayoutSeat> seats = seatRepository
            .findByAuditoriumIdAndLayoutVersionOrderByRowLabelAscColNoAsc(auditoriumId, version).stream()
            .map(s -> new LayoutSeat(s.getSeatId(), s.getSeatLabel(), s.getRowLabel(), s.getColNo(),
                s.getX(), s.getY(), s.getW(), s.getH(), s.getZoneNo(), SeatType.fromCode(s.getSourceSeatCode()), s.isActive()))
            .toList();

        return new AuditoriumLayoutResponse(
            auditorium.getAuditoriumId(), auditorium.getName(), ScreenType.fromCode(auditorium.getScreenTypeCode()),
            version, auditorium.getLayoutWidth(), auditorium.getLayoutHeight(),
            new Screen(layout.getScreenXFrom(), layout.getScreenXTo()),
            elements(layout.getEntrances(), n -> new Entrance(decimal(n, "x"), decimal(n, "y"))),
            elements(layout.getVerticalAisles(), n -> new VerticalAisle(decimal(n, "x_from"), decimal(n, "x_to"))),
            elements(layout.getHorizontalAisles(), n -> new HorizontalAisle(decimal(n, "y_from"), decimal(n, "y_to"))),
            seats,
            new Source(layout.getSourceProvider(), Times.toApi(layout.getFetchedAt())));
    }

    /** 시드 JSON(snake_case 배열) → 계약 모양. 계약에 없는 키(label·rows·after_row 등)는 버린다 */
    private <T> List<T> elements(String json, Function<JsonNode, T> toElement) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = mapper.readTree(json);
            List<T> out = new ArrayList<>();
            array.forEach(node -> out.add(toElement.apply(node)));
            return out;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("auditorium_layout JSON 형식 오류", e);
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.decimalValue();
    }
}
