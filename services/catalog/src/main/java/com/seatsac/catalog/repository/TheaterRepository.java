package com.seatsac.catalog.repository;

import com.seatsac.catalog.entity.Theater;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TheaterRepository extends JpaRepository<Theater, Long> {

    List<Theater> findByHasLayoutOrderByTheaterId(boolean hasLayout);

    List<Theater> findByHasLayoutAndRegionOrderByTheaterId(boolean hasLayout, String region);

    List<Theater> findByTheaterIdInOrderByTheaterId(Collection<Long> theaterIds);
}
