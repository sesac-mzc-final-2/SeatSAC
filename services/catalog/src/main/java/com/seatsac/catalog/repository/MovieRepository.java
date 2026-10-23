package com.seatsac.catalog.repository;

import com.seatsac.catalog.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MovieRepository extends JpaRepository<Movie, Long> {
}
