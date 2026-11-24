package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.EndpointPublication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EndpointPublicationRepository extends JpaRepository<EndpointPublication, Long> {
    List<EndpointPublication> findBySpaceIdOrderByIdDesc(Long spaceId);
    List<EndpointPublication> findByDefinitionIdOrderByIdDesc(Long definitionId);
    long countByDefinitionId(Long definitionId);
}
