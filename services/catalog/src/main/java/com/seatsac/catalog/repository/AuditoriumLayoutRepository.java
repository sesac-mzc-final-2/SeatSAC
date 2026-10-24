package com.seatsac.catalog.repository;

import com.seatsac.catalog.entity.AuditoriumLayout;
import com.seatsac.catalog.entity.AuditoriumLayout.LayoutId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriumLayoutRepository extends JpaRepository<AuditoriumLayout, LayoutId> {
}
