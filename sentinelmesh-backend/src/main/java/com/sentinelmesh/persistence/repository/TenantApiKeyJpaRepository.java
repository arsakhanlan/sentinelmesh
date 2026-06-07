package com.sentinelmesh.persistence.repository;

import com.sentinelmesh.persistence.entity.TenantApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantApiKeyJpaRepository extends JpaRepository<TenantApiKeyEntity, UUID> {
    Optional<TenantApiKeyEntity> findByApiKeyHash(String apiKeyHash);
}
