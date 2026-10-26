package com.seatsac.user.repository;

import com.seatsac.user.entity.UserPreferredTheater;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserPreferredTheaterRepository
    extends JpaRepository<UserPreferredTheater, UserPreferredTheater.PreferredTheaterId> {

    List<UserPreferredTheater> findByIdUserIdOrderByIdRankNo(String userId);

    /**
     * 전체 교체 전에 바로 지운다(벌크). 엔티티 삭제로 두면 Hibernate가 같은 flush에서 INSERT를 DELETE보다 먼저 보내
     * (user_id, theater_id) 유일 키에 걸린다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserPreferredTheater t where t.id.userId = :userId")
    int deleteAllByUserId(@Param("userId") String userId);
}
