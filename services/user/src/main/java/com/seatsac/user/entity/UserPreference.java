package com.seatsac.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 선호 시간대·장르 — {@code user_preference} (PK = user_id, FK user). 선호 극장은 {@link UserPreferredTheater}.
 * {@code preferredTimeSlot}은 DDL이 VARCHAR(10)이라 문자열로 두고 DTO에서 enum으로 바꾼다
 * (MySQL ENUM 금지 규칙 — 규약 3장). 장르는 저장·전달만 한다(2.2.4 D6).
 */
@Entity
@Table(name = "user_preference")
public class UserPreference implements Persistable<String> {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "preferred_time_slot", length = 10)
    private String preferredTimeSlot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferred_genres", nullable = false)
    private List<String> preferredGenres;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected UserPreference() {
    }

    public UserPreference(String userId, String preferredTimeSlot, List<String> preferredGenres, Instant now) {
        this.userId = userId;
        replace(preferredTimeSlot, preferredGenres, now);
    }

    public void replace(String preferredTimeSlot, List<String> preferredGenres, Instant now) {
        this.preferredTimeSlot = preferredTimeSlot;
        this.preferredGenres = preferredGenres == null ? List.of() : List.copyOf(preferredGenres);
        this.updatedAt = now;
    }

    @Override
    public String getId() {
        return userId;
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

    public String getUserId() {
        return userId;
    }

    public String getPreferredTimeSlot() {
        return preferredTimeSlot;
    }

    public List<String> getPreferredGenres() {
        return preferredGenres == null ? List.of() : List.copyOf(preferredGenres);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
