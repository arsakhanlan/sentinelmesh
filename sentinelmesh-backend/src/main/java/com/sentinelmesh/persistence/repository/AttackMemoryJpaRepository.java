package com.sentinelmesh.persistence.repository;

import com.sentinelmesh.persistence.entity.AttackMemoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AttackMemoryJpaRepository extends JpaRepository<AttackMemoryEntity, UUID> {

    /**
     * Most-recent N entries, newest first. Used at startup to hydrate the
     * in-memory bank up to its size cap.
     */
    List<AttackMemoryEntity> findAllByOrderByAddedAtDesc(Pageable pageable);
}
