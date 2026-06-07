package com.sentinelmesh.domain.service;

import com.sentinelmesh.common.error.NotFoundException;
import com.sentinelmesh.common.util.UuidV7;
import com.sentinelmesh.domain.model.AgentEvent;
import com.sentinelmesh.domain.model.Approval;
import com.sentinelmesh.domain.model.Decision;
import com.sentinelmesh.domain.port.in.DecideApprovalUseCase;
import com.sentinelmesh.domain.port.out.ApprovalRepository;
import com.sentinelmesh.domain.port.out.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ApprovalService implements DecideApprovalUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final ApprovalRepository approvals;
    private final EventPublisher publisher;
    private final Clock clock;

    public ApprovalService(ApprovalRepository approvals, EventPublisher publisher, Clock clock) {
        this.approvals = approvals;
        this.publisher = publisher;
        this.clock = clock;
    }

    public Approval request(UUID sessionId, UUID actionId, String intent,
                            Map<String, Object> payload, double blastRadius) {
        Instant now = clock.instant();
        Approval a = new Approval(UuidV7.next(), sessionId, actionId, payload,
                null, null, null, blastRadius, intent,
                now, null, now.plus(DEFAULT_TTL), Approval.Status.PENDING);
        Approval saved = approvals.save(a);
        publisher.publish(new AgentEvent.ApprovalRequested(
                UuidV7.next(), sessionId, now, "sentinel",
                saved.id(), intent, payload, blastRadius));
        return saved;
    }

    @Override
    @Transactional
    public Approval decide(UUID approvalId, Decision decision, String approverId,
                            Map<String, Object> modifiedPayload) {
        Approval a = approvals.findById(approvalId)
                .orElseThrow(() -> new NotFoundException("Approval not found: " + approvalId));
        if (a.status() != Approval.Status.PENDING) {
            throw new IllegalStateException("Approval already decided: " + a.status());
        }
        Instant now = clock.instant();
        Approval decided = a.decide(decision, approverId, modifiedPayload, now);
        Approval saved = approvals.save(decided);
        publisher.publish(new AgentEvent.ApprovalDecided(
                UuidV7.next(), a.sessionId(), now, "user",
                a.id(), decision, approverId));
        return saved;
    }

    @Override
    public List<Approval> pending() { return approvals.findPending(); }

    @Override
    public Approval get(UUID id) {
        return approvals.findById(id)
                .orElseThrow(() -> new NotFoundException("Approval not found: " + id));
    }

    /** Sweep expired approvals every 30 seconds. */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void expireStale() {
        Instant now = clock.instant();
        List<Approval> expired = approvals.findExpired(now);
        for (Approval a : expired) {
            Approval exp = new Approval(a.id(), a.sessionId(), a.actionId(),
                    a.requestedPayload(), null, Decision.BLOCK, null,
                    a.blastRadius(), a.intent(),
                    a.requestedAt(), now, a.ttlAt(), Approval.Status.EXPIRED);
            approvals.save(exp);
            log.info("Approval expired: {} (session={})", a.id(), a.sessionId());
        }
    }
}
