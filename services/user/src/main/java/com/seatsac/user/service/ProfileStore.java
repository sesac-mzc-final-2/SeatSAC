package com.seatsac.user.service;

import com.seatsac.user.entity.User;
import com.seatsac.user.repository.UserRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로필 쓰기 트랜잭션. {@link ProfileService}는 Cognito 호출(HTTP) 동안 DB 연결을 잡지 않도록
 * 트랜잭션 밖에서 흐름을 잡고, 쓰기만 여기로 넘긴다(같은 클래스 안의 {@code @Transactional} 호출은 프록시를 타지 않는다).
 */
@Component
public class ProfileStore {

    private final UserRepository userRepository;

    public ProfileStore(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** INSERT 한 번. PK·email 유일 키에 걸리면 {@code DataIntegrityViolationException}이 그대로 올라온다 */
    @Transactional
    public User create(User user) {
        return userRepository.saveAndFlush(user);
    }

    @Transactional
    public User rename(String userId, String name, Instant now) {
        User user = userRepository.findById(userId).orElseThrow();
        user.rename(name, now);
        return user;
    }
}
