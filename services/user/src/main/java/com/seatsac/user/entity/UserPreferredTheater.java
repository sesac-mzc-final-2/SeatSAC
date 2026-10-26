package com.seatsac.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 선호 극장 — {@code user_preferred_theater} (PK: user_id, rank_no · UK: user_id, theater_id).
 * {@code theaterId}는 catalog 참조(FK 없음, 데이터 모델 3장). PUT은 전체 교체라 행을 지우고 다시 넣는다.
 */
@Entity
@Table(name = "user_preferred_theater")
public class UserPreferredTheater implements Persistable<UserPreferredTheater.PreferredTheaterId> {

    @EmbeddedId
    private PreferredTheaterId id;

    @Column(name = "theater_id", nullable = false)
    private Long theaterId;

    @Transient
    private boolean isNew = true;

    protected UserPreferredTheater() {
    }

    public UserPreferredTheater(String userId, int rankNo, Long theaterId) {
        this.id = new PreferredTheaterId(userId, rankNo);
        this.theaterId = theaterId;
    }

    @Override
    public PreferredTheaterId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public int getRankNo() {
        return id.rankNo;
    }

    public Long getTheaterId() {
        return theaterId;
    }

    @Embeddable
    public static class PreferredTheaterId implements Serializable {

        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "user_id", length = 36)
        private String userId;

        /** DDL TINYINT (1~3) */
        @JdbcTypeCode(SqlTypes.TINYINT)
        @Column(name = "rank_no")
        private int rankNo;

        protected PreferredTheaterId() {
        }

        public PreferredTheaterId(String userId, int rankNo) {
            this.userId = userId;
            this.rankNo = rankNo;
        }

        public String getUserId() {
            return userId;
        }

        public int getRankNo() {
            return rankNo;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PreferredTheaterId other
                && rankNo == other.rankNo && Objects.equals(userId, other.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, rankNo);
        }
    }
}
