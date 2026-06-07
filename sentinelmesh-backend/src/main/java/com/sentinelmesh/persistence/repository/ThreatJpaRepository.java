package com.sentinelmesh.persistence.repository;

import com.sentinelmesh.persistence.entity.ThreatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ThreatJpaRepository extends JpaRepository<ThreatEntity, UUID> {
    List<ThreatEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
    long countByCategory(String category);

    @Query(value = """
            SELECT s.tenant_id, COUNT(*) FROM threats t
            JOIN sessions s ON t.session_id = s.id
            WHERE s.tenant_id IS NOT NULL
            GROUP BY s.tenant_id
            """, nativeQuery = true)
    List<Object[]> countThreatsGroupedByTenant();
}
