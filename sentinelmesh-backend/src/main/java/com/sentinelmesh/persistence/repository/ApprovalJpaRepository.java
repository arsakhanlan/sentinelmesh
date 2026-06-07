package com.sentinelmesh.persistence.repository;

import com.sentinelmesh.persistence.entity.ApprovalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ApprovalJpaRepository extends JpaRepository<ApprovalEntity, UUID> {
    List<ApprovalEntity> findByStatusOrderByRequestedAtAsc(String status);

    List<ApprovalEntity> findBySessionIdOrderByRequestedAtAsc(UUID sessionId);

    @Query("SELECT a FROM ApprovalEntity a WHERE a.status = 'PENDING' AND a.ttlAt < :now")
    List<ApprovalEntity> findExpired(@Param("now") Instant now);
}
