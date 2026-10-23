package com.seatsac.catalog.repository;

import com.seatsac.catalog.entity.Screening;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 회차 조회. 응답에 영화·상영관·극장 이름이 함께 들어가므로 전부 fetch join 해 N+1을 막는다
 * ({@code open-in-view=false}라 지연 로딩에 의존하지 않는다).
 */
public interface ScreeningRepository extends JpaRepository<Screening, Long> {

    String WITH_RELATIONS = "select s from Screening s join fetch s.movie m join fetch s.auditorium a join fetch a.theater t ";

    @Query(WITH_RELATIONS + "where s.screeningId = :screeningId")
    Optional<Screening> findWithRelationsById(@Param("screeningId") Long screeningId);

    @Query(WITH_RELATIONS + "where s.businessDate = :date and t.theaterId = :theaterId "
        + "order by s.startsAt, s.screeningId")
    List<Screening> findByDateAndTheater(@Param("date") LocalDate date, @Param("theaterId") Long theaterId);

    @Query(WITH_RELATIONS + "where s.businessDate = :date and t.theaterId = :theaterId and m.movieId = :movieId "
        + "order by s.startsAt, s.screeningId")
    List<Screening> findByDateAndTheaterAndMovie(@Param("date") LocalDate date, @Param("theaterId") Long theaterId,
                                                 @Param("movieId") Long movieId);

    @Query(WITH_RELATIONS + "where s.screeningId in :screeningIds order by s.screeningId")
    List<Screening> findWithRelationsByIds(@Param("screeningIds") Collection<Long> screeningIds);
}
