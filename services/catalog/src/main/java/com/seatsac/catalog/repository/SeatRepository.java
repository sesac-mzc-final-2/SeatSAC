package com.seatsac.catalog.repository;

import com.seatsac.catalog.entity.Seat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** 배치 버전의 전체 좌석(비활성 포함 — 화면이 active로 구분한다). 행·열 순 */
    List<Seat> findByAuditoriumIdAndLayoutVersionOrderByRowLabelAscColNoAsc(Long auditoriumId, Integer layoutVersion);
}
