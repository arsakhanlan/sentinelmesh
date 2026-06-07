package com.sentinelmesh.persistence.adapter;

import com.sentinelmesh.domain.model.Approval;
import com.sentinelmesh.domain.port.out.ApprovalRepository;
import com.sentinelmesh.persistence.mapper.EntityMappers;
import com.sentinelmesh.persistence.repository.ApprovalJpaRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JpaApprovalRepository implements ApprovalRepository {

    private final ApprovalJpaRepository jpa;

    public JpaApprovalRepository(ApprovalJpaRepository jpa) { this.jpa = jpa; }

    @Override
    public Approval save(Approval a) {
        return EntityMappers.toDomain(jpa.save(EntityMappers.toEntity(a)));
    }

    @Override
    public Optional<Approval> findById(UUID id) {
        return jpa.findById(id).map(EntityMappers::toDomain);
    }

    @Override
    public List<Approval> findPending() {
        return jpa.findByStatusOrderByRequestedAtAsc("PENDING").stream()
                .map(EntityMappers::toDomain).toList();
    }

    @Override
    public List<Approval> findExpired(Instant now) {
        return jpa.findExpired(now).stream().map(EntityMappers::toDomain).toList();
    }

    @Override
    public List<Approval> findBySession(UUID sessionId) {
        if (sessionId == null) return List.of();
        return jpa.findBySessionIdOrderByRequestedAtAsc(sessionId).stream()
                .map(EntityMappers::toDomain).toList();
    }
}
