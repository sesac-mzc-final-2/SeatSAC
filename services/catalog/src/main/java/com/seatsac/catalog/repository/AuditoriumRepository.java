package com.seatsac.catalog.repository;

import com.seatsac.catalog.entity.Auditorium;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriumRepository extends JpaRepository<Auditorium, Long> {
}
