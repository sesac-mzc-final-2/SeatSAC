package com.seatsac.catalog.service;

import com.seatsac.catalog.common.CatalogErrorCode;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse.LayoutSeat;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse.Screen;
import com.seatsac.catalog.dto.AuditoriumLayoutResponse.Source;
import com.seatsac.catalog.dto.ScreenType;
import com.seatsac.catalog.dto.SeatType;
import com.seatsac.catalog.entity.Auditorium;
import com.seatsac.catalog.entity.AuditoriumLayout;
import com.seatsac.catalog.entity.AuditoriumLayout.LayoutId;
import com.seatsac.catalog.repository.AuditoriumLayoutRepository;
import com.seatsac.catalog.repository.AuditoriumRepository;
import com.seatsac.catalog.repository.SeatRepository;
import com.seatsac.common.core.time.Times;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAT-04 배치도의 DB 읽기 — 상영관·배치·좌석 세 조회를 읽기 전용 트랜잭션 하나로 묶는다.
 * Valkey 조회·쓰기는 {@link LayoutService}(트랜잭션 밖)가 하므로 캐시 대기가 DB 커넥션을 잡지 않는다.
 */
@Service
public class LayoutLoader {

    private final AuditoriumRepository auditoriumRepository;
    private final AuditoriumLayoutRepository layoutRepository;
    private final SeatRepository seatRepository;

    public LayoutLoader(AuditoriumRepository auditoriumRepository, AuditoriumLayoutRepository layoutRepository,
                        SeatRepository seatRepository) {
        this.auditoriumRepository = auditoriumRepository;
        this.layoutRepository = layoutRepository;
        this.seatRepository = seatRepository;
    }

    /** 상영관의 현재(최신) 배치 버전 */
    @Transactional(readOnly = true)
    public int currentVersion(Long auditoriumId) {
        return auditorium(auditoriumId).getLayoutVersion();
    }

    @Transactional(readOnly = true)
    public AuditoriumLayoutResponse load(Long auditoriumId, int version) {
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
            LayoutJson.entrances(layout.getEntrances()),
            LayoutJson.verticalAisles(layout.getVerticalAisles()),
            LayoutJson.horizontalAisles(layout.getHorizontalAisles()),
            seats,
            new Source(layout.getSourceProvider(), Times.toApi(layout.getFetchedAt())));
    }

    private Auditorium auditorium(Long auditoriumId) {
        return auditoriumRepository.findById(auditoriumId).orElseThrow(CatalogErrorCode.AUDITORIUM_NOT_FOUND::exception);
    }
}
