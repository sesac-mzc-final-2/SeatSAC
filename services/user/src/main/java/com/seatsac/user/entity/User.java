package com.seatsac.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 프로필 — {@code user} (user V1). {@code userId} = Cognito {@code sub}(CHAR(36)). password·role 없음(ADR-37).
 * 행은 첫 {@code GET /users/me} 때 Cognito {@code GetUser} 값으로 만든다. {@code name}은 Cognito에 없으면 빈 문자열로
 * 저장하고 응답에서는 null로 내보낸다(DDL NOT NULL · 계약 nullable).
 *
 * <p>ID를 앱이 정하므로 {@link Persistable}로 "새 행"을 알려 {@code save}가 SELECT 없이 INSERT만 하게 한다.
 */
@Entity
@Table(name = "`user`")
public class User implements Persistable<String> {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "name", nullable = false, length = 2048)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected User() {
    }

    public User(String userId, String email, String name, Instant now) {
        this.userId = userId;
        this.email = email;
        this.name = name == null ? "" : name;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void rename(String name, Instant now) {
        this.name = name;
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

    public String getEmail() {
        return email;
    }

    /** 저장 값. 비어 있으면 응답에서 null (DTO가 처리) */
    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
