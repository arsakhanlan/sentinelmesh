package com.sentinelmesh.domain.port.in;

import com.sentinelmesh.domain.model.Session;

import java.util.UUID;

public interface StartSessionUseCase {
    Session start(String userId, String goal, String policyBundleId);
    Session get(UUID id);
}
