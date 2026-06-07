package com.sentinelmesh.api.rest;

import com.sentinelmesh.api.dto.FireScenarioRequest;
import com.sentinelmesh.api.dto.FireScenarioResponse;
import com.sentinelmesh.domain.port.in.FireAdversaryScenarioUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/adversary")
@Tag(name = "Adversary", description = "Demo attack scenarios — fire to drive the security pipeline")
public class AdversaryController {

    private final FireAdversaryScenarioUseCase adversary;

    public AdversaryController(FireAdversaryScenarioUseCase adversary) { this.adversary = adversary; }

    @GetMapping("/scenarios")
    public List<FireAdversaryScenarioUseCase.ScenarioInfo> list() {
        return adversary.listScenarios();
    }

    @PostMapping("/fire")
    public ResponseEntity<FireScenarioResponse> fire(@Valid @RequestBody FireScenarioRequest req) {
        UUID sid = adversary.fire(req.scenarioId(), req.sessionId());
        return ResponseEntity.accepted().body(new FireScenarioResponse(
                sid, req.scenarioId(), "Scenario playing asynchronously. Watch /ws/sessions/" + sid));
    }
}
