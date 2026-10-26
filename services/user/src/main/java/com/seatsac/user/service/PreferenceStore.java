package com.seatsac.user.service;

import com.seatsac.user.dto.PreferencesDto;
import com.seatsac.user.dto.TimeSlot;
import com.seatsac.user.entity.UserPreference;
import com.seatsac.user.entity.UserPreferredTheater;
import com.seatsac.user.repository.UserPreferenceRepository;
import com.seatsac.user.repository.UserPreferredTheaterRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 선호 정보 읽기·전체 교체 트랜잭션. 검증·catalog 호출은 {@link PreferenceService}가 트랜잭션 밖에서 한다 */
@Component
public class PreferenceStore {

    private final UserPreferenceRepository preferenceRepository;
    private final UserPreferredTheaterRepository theaterRepository;

    public PreferenceStore(UserPreferenceRepository preferenceRepository,
                           UserPreferredTheaterRepository theaterRepository) {
        this.preferenceRepository = preferenceRepository;
        this.theaterRepository = theaterRepository;
    }

    @Transactional(readOnly = true)
    public PreferencesDto find(String userId) {
        Optional<UserPreference> preference = preferenceRepository.findById(userId);
        List<UserPreferredTheater> theaters = theaterRepository.findByIdUserIdOrderByIdRankNo(userId);
        if (preference.isEmpty() && theaters.isEmpty()) {
            return PreferencesDto.empty();
        }
        return toDto(preference.orElse(null), theaters);
    }

    /** 극장 행은 지우고 다시 넣고, 시간대·장르 행은 있으면 고치고 없으면 만든다 */
    @Transactional
    public PreferencesDto replace(String userId, PreferencesDto request, Instant now) {
        String timeSlot = request.preferredTimeSlot() == null ? null : request.preferredTimeSlot().name();
        UserPreference preference = preferenceRepository.findById(userId)
            .map(existing -> {
                existing.replace(timeSlot, request.preferredGenres(), now);
                return existing;
            })
            .orElseGet(() -> preferenceRepository.save(
                new UserPreference(userId, timeSlot, request.preferredGenres(), now)));

        theaterRepository.deleteAllByUserId(userId);
        List<UserPreferredTheater> theaters = request.preferredTheaters().stream()
            .sorted(Comparator.comparingInt(PreferencesDto.PreferredTheater::rank))
            .map(t -> new UserPreferredTheater(userId, t.rank(), t.theaterId()))
            .toList();
        theaterRepository.saveAll(theaters);
        return toDto(preference, theaters);
    }

    private static PreferencesDto toDto(UserPreference preference, List<UserPreferredTheater> theaters) {
        List<PreferencesDto.PreferredTheater> preferredTheaters = theaters.stream()
            .map(t -> new PreferencesDto.PreferredTheater(t.getRankNo(), t.getTheaterId()))
            .toList();
        TimeSlot slot = preference == null || preference.getPreferredTimeSlot() == null
            ? null : TimeSlot.valueOf(preference.getPreferredTimeSlot());
        List<String> genres = preference == null ? List.of() : preference.getPreferredGenres();
        return new PreferencesDto(preferredTheaters, slot, genres);
    }
}
