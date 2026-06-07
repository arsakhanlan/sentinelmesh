package com.sentinelmesh.persistence.adapter;

import com.sentinelmesh.domain.model.Policy;
import com.sentinelmesh.domain.port.out.PolicyRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * In-memory PolicyRepository for v1. Policies are loaded from a YAML resource
 * by {@link com.sentinelmesh.policy.PolicyEngine}; this repo just exposes the
 * current bundle to the API for inspection/listing.
 *
 * <p>v2 would persist policy versions to Postgres with a {@code policies} table.
 */
@Component
public class InMemoryPolicyRepository implements PolicyRepository {

    private final Map<UUID, Policy> byId = new LinkedHashMap<>();

    @Override
    public synchronized Policy save(Policy p) {
        byId.put(p.id(), p);
        return p;
    }

    @Override public Optional<Policy> findById(UUID id) { return Optional.ofNullable(byId.get(id)); }

    @Override
    public Optional<Policy> findLatestByName(String name) {
        return byId.values().stream().filter(p -> p.name().equals(name))
                .max(Comparator.comparingInt(Policy::version));
    }

    @Override public List<Policy> findAll() { return List.copyOf(byId.values()); }
}
