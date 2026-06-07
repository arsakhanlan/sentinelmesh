package com.sentinelmesh.persistence.repository;

import com.sentinelmesh.persistence.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface SessionJpaRepository extends JpaRepository<SessionEntity, UUID> {

    long countByTenantIdAndCreatedAtGreaterThanEqual(UUID tenantId, Instant since);

    /** Count sessions created since a point in time. */
    @Query("SELECT COUNT(s) FROM SessionEntity s WHERE s.createdAt >= :since")
    long countSessionsCreatedSince(@Param("since") Instant since);
}
