package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.EndpointDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EndpointDefinitionRepository extends JpaRepository<EndpointDefinition, Long> {
    Optional<EndpointDefinition> findByCode(String code);
    List<EndpointDefinition> findBySpaceIdOrderByIdAsc(Long spaceId);
}
