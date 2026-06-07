package com.sentinelmesh.api.rest;

import com.sentinelmesh.security.memory.AttackMemory;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Demo-prep / SOC-operator admin endpoints. Kept narrow on purpose — the
 * goal is to give a SOC engineer a reliable "reset to a clean state" lever
 * without nuking the database.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Operational levers for demo + SOC prep")
public class AdminController {

    private final AttackMemory attackMemory;

    public AdminController(AttackMemory attackMemory) {
        this.attackMemory = attackMemory;
    }

    /**
     * Drop runtime-learned entries from L7 attack memory, leaving the
     * hand-curated seed set intact. Use when the bank has been poisoned by
     * earlier false positives (e.g. an over-eager L3 stub regex) and the
     * legitimate happy-path browses are now mistakenly matching.
     */
    @PostMapping("/attack-memory/prune")
    public Map<String, Object> pruneAttackMemory() {
        int sizeBefore = attackMemory.size();
        int pruned = attackMemory.pruneRuntimeEntries();
        return Map.of(
                "pruned", pruned,
                "size_before", sizeBefore,
                "size_after", attackMemory.size(),
                "note", "Hand-curated seed entries preserved.");
    }
}
