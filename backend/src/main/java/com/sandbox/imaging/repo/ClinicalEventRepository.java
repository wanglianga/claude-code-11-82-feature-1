package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.ClinicalEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClinicalEventRepository extends JpaRepository<ClinicalEvent, Long> {
    List<ClinicalEvent> findByPatientIdInOrderByOccurredAtAsc(List<Long> patientIds);
    List<ClinicalEvent> findByBackfillTrue();
    Optional<ClinicalEvent> findByEventKey(String eventKey);
}
