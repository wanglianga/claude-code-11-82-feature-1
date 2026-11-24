package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.BridgeReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BridgeReportRepository extends JpaRepository<BridgeReport, Long> {
    List<BridgeReport> findBySpaceIdOrderByIdDesc(Long spaceId);
    List<BridgeReport> findByPublicationIdOrderByIdDesc(Long publicationId);
}
