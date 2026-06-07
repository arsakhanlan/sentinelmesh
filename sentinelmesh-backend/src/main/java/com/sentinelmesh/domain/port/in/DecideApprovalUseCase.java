package com.sentinelmesh.domain.port.in;

import com.sentinelmesh.domain.model.Approval;
import com.sentinelmesh.domain.model.Decision;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DecideApprovalUseCase {
    Approval decide(UUID approvalId, Decision decision, String approverId,
                    Map<String, Object> modifiedPayload);
    List<Approval> pending();
    Approval get(UUID id);
}
