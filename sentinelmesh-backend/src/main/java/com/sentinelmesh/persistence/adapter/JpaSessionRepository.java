package com.sentinelmesh.persistence.adapter;

import com.sentinelmesh.domain.model.Session;
import com.sentinelmesh.domain.port.out.SessionRepository;
import com.sentinelmesh.persistence.mapper.EntityMappers;
import com.sentinelmesh.persistence.repository.SessionJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class JpaSessionRepository implements SessionRepository {

    private final SessionJpaRepository jpa;

    public JpaSessionRepository(SessionJpaRepository jpa) { this.jpa = jpa; }

    @Override
    public Session save(Session session) {
        return EntityMappers.toDomain(jpa.save(EntityMappers.toEntity(session)));
    }

    @Override
    public Optional<Session> findById(UUID id) {
        return jpa.findById(id).map(EntityMappers::toDomain);
    }
}
