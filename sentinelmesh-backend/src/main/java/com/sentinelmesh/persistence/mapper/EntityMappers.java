package com.sentinelmesh.persistence.mapper;

import com.sentinelmesh.domain.model.*;
import com.sentinelmesh.persistence.entity.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Hand-rolled mappers. No annotation processor needed; trivially testable. */
public final class EntityMappers {

    private EntityMappers() {}

    public static SessionEntity toEntity(Session s) {
        return new SessionEntity(s.id(), s.userId(), s.goal(), s.status().name(),
                s.policyBundleId(),
                s.capabilityToken() == null ? Map.of() : s.capabilityToken(),
                s.createdAt(), s.endedAt(), s.tenantId());
    }

    public static Session toDomain(SessionEntity e) {
        return new Session(e.getId(), e.getUserId(), e.getGoal(),
                Session.Status.valueOf(e.getStatus()), e.getPolicyBundleId(),
                e.getCapabilityToken(), e.getCreatedAt(), e.getEndedAt(), e.getTenantId());
    }

    public static ThreatEntity toEntity(Threat t) {
        return new ThreatEntity(t.id(), t.sessionId(), t.actionId(),
                t.category(), t.severity().name(),
                BigDecimal.valueOf(t.score()).setScale(3, RoundingMode.HALF_UP),
                t.evidence() == null ? Map.of() : t.evidence(),
                t.createdAt());
    }

    public static Threat toDomain(ThreatEntity e) {
        return new Threat(e.getId(), e.getSessionId(), e.getActionId(),
                e.getCategory(), Threat.Severity.valueOf(e.getSeverity()),
                e.getScore().doubleValue(), e.getEvidence(), e.getCreatedAt());
    }

    public static ApprovalEntity toEntity(Approval a) {
        return new ApprovalEntity(a.id(), a.sessionId(), a.actionId(),
                a.requestedPayload(), a.approverId(),
                a.decision() == null ? null : a.decision().name(),
                a.modifiedPayload(),
                a.blastRadius() == 0.0 ? BigDecimal.ZERO
                        : BigDecimal.valueOf(a.blastRadius()).setScale(3, RoundingMode.HALF_UP),
                a.intent(), a.requestedAt(), a.decidedAt(), a.ttlAt(),
                a.status().name());
    }

    public static Approval toDomain(ApprovalEntity e) {
        return new Approval(e.getId(), e.getSessionId(), e.getActionId(),
                e.getRequestedPayload(), e.getApproverId(),
                e.getDecision() == null ? null : Decision.valueOf(e.getDecision()),
                e.getModifiedPayload(),
                e.getBlastRadius() == null ? 0.0 : e.getBlastRadius().doubleValue(),
                e.getIntent(), e.getRequestedAt(), e.getDecidedAt(), e.getTtlAt(),
                Approval.Status.valueOf(e.getStatus()));
    }

    public static AuditEvent toDomain(AuditEventEntity e) {
        return new AuditEvent(e.getSequence(), e.getEventId(), e.getSessionId(),
                e.getTs(), e.getKind(), e.getActor(),
                e.getPayload(), e.getPrevHash(), e.getHash());
    }
}
