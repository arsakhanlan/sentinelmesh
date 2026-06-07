package com.sentinelmesh.persistence.repository;

import com.sentinelmesh.persistence.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface SessionJpaRepository extends JpaRepository<SessionEntity, UUID> {

    long countByTenantIdAndCreatedAtGreaterThanEqual(UUID tenantId, Instant since);
}
