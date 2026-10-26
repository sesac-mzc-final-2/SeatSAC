package com.seatsac.user.repository;

import com.seatsac.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, String> {

    /**
     * 사용자 행을 {@code SELECT ... FOR UPDATE}로 잡는다 — 같은 사용자의 선호 전체 교체(USR-05)를 직렬화하는 용도.
     * 트랜잭션 안에서만 부른다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.userId = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") String userId);
}
