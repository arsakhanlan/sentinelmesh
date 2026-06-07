package com.sentinelmesh.persistence.adapter;

import com.sentinelmesh.domain.model.Threat;
import com.sentinelmesh.domain.port.out.ThreatRepository;
import com.sentinelmesh.persistence.mapper.EntityMappers;
import com.sentinelmesh.persistence.repository.ThreatJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class JpaThreatRepository implements ThreatRepository {

    private final ThreatJpaRepository jpa;

    public JpaThreatRepository(ThreatJpaRepository jpa) { this.jpa = jpa; }

    @Override
    public Threat save(Threat t) {
        return EntityMappers.toDomain(jpa.save(EntityMappers.toEntity(t)));
    }

    @Override
    public List<Threat> findBySession(UUID sessionId) {
        return jpa.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(EntityMappers::toDomain).toList();
    }

    @Override
    public long countAll() { return jpa.count(); }

    @Override
    public long countByCategory(String category) { return jpa.countByCategory(category); }
}
