package com.sentinelmesh.api.rest;

import com.sentinelmesh.api.dto.CreateSessionRequest;
import com.sentinelmesh.api.dto.SessionResponse;
import com.sentinelmesh.api.dto.ThreatResponse;
import com.sentinelmesh.domain.port.in.StartSessionUseCase;
import com.sentinelmesh.domain.port.out.ThreatRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sessions")
@Tag(name = "Sessions", description = "Agent session lifecycle")
public class SessionController {

    private final StartSessionUseCase sessions;
    private final ThreatRepository threats;

    public SessionController(StartSessionUseCase sessions, ThreatRepository threats) {
        this.sessions = sessions;
        this.threats = threats;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> create(@Valid @RequestBody CreateSessionRequest req) {
        var s = sessions.start(req.userId(), req.goal(), req.policyBundleId());
        return ResponseEntity.created(URI.create("/api/v1/sessions/" + s.id()))
                .body(SessionResponse.from(s));
    }

    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable UUID id) {
        return SessionResponse.from(sessions.get(id));
    }

    @GetMapping("/{id}/threats")
    public List<ThreatResponse> threats(@PathVariable UUID id) {
        return threats.findBySession(id).stream().map(ThreatResponse::from).toList();
    }
}
