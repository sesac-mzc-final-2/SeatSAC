package com.seatsac.catalog.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.seatsac.catalog.cache.CacheKeys;
import com.seatsac.catalog.cache.CatalogCache;
import com.seatsac.catalog.common.CatalogErrorCode;
import com.seatsac.catalog.dto.MovieDetail;
import com.seatsac.catalog.dto.MovieStatus;
import com.seatsac.catalog.dto.MovieSummary;
import com.seatsac.catalog.repository.MovieRepository;
import com.seatsac.common.core.PageResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** CAT-01·02. 목록은 (상태, 페이지, 크기)마다, 상세는 ID마다 캐시(TTL 10분). 404는 캐시하지 않는다 */
@Service
public class MovieService {

    private static final TypeReference<PageResponse<MovieSummary>> PAGE_TYPE = new TypeReference<>() {
    };

    private final MovieRepository movieRepository;
    private final CatalogCache cache;

    public MovieService(MovieRepository movieRepository, CatalogCache cache) {
        this.movieRepository = movieRepository;
        this.cache = cache;
    }

    public PageResponse<MovieSummary> list(MovieStatus status, int page, int size) {
        if (status == MovieStatus.COMING_SOON) {
            // 시드에 개봉 예정작이 없다(계약 description). DB·캐시를 거치지 않는다
            return PageResponse.of(List.of(), page, size, 0);
        }
        return cache.getOrLoad(CacheKeys.movies(status.name(), page, size), PAGE_TYPE, () -> {
            Page<MovieSummary> found = movieRepository
                .findAll(PageRequest.of(page, size, Sort.by("movieId")))
                .map(MovieSummary::from);
            return PageResponse.of(found.getContent(), page, size, found.getTotalElements());
        });
    }

    public MovieDetail get(Long movieId) {
        return cache.getOrLoad(CacheKeys.movie(movieId), MovieDetail.class, () -> movieRepository.findById(movieId)
            .map(MovieDetail::from)
            .orElseThrow(CatalogErrorCode.MOVIE_NOT_FOUND::exception));
    }
}
