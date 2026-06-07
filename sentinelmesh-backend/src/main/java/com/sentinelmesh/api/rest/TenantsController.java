package com.sentinelmesh.api.rest;

import com.sentinelmesh.persistence.entity.TenantEntity;
import com.sentinelmesh.persistence.repository.SessionJpaRepository;
import com.sentinelmesh.persistence.repository.TenantJpaRepository;
import com.sentinelmesh.persistence.repository.ThreatJpaRepository;
import com.sentinelmesh.security.budget.BudgetTracker;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tenant utilization for the SOC /tenants dashboard.
 */
@RestController
@RequestMapping("/api/v1/tenants")
@Tag(name = "Tenants", description = "Multi-tenant utilization")
public class TenantsController {

    private final TenantJpaRepository tenants;
    private final SessionJpaRepository sessions;
    private final ThreatJpaRepository threats;
    private final BudgetTracker budgets;
    private final Clock clock;

    public TenantsController(TenantJpaRepository tenants, SessionJpaRepository sessions,
                             ThreatJpaRepository threats, BudgetTracker budgets, Clock clock) {
        this.tenants = tenants;
        this.sessions = sessions;
        this.threats = threats;
        this.budgets = budgets;
        this.clock = clock;
    }

    @GetMapping("/summary")
    public List<Map<String, Object>> summary() {
        Instant dayStart = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<UUID, Long> threatByTenant = new HashMap<>();
        for (Object[] row : threats.countThreatsGroupedByTenant()) {
            if (row[0] == null) continue;
            UUID tid = row[0] instanceof UUID u ? u : UUID.fromString(row[0].toString());
            long c = row[1] instanceof Number n ? n.longValue() : Long.parseLong(row[1].toString());
            threatByTenant.put(tid, c);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (TenantEntity t : tenants.findAll()) {
            UUID tid = t.getId();
            long sessionsToday = sessions.countByTenantIdAndCreatedAtGreaterThanEqual(tid, dayStart);
            BudgetTracker.TenantSnapshot snap = budgets.tenantSnapshot(tid);

            Map<String, Object> tools = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> cap : snap.toolCaps().entrySet()) {
                String tool = cap.getKey();
                int capV = cap.getValue();
                int used = snap.toolUsed24h().getOrDefault(tool, 0);
                double pct = capV <= 0 ? 0.0 : Math.min(100.0, 100.0 * used / capV);
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("used24h", used);
                one.put("cap24h", capV);
                one.put("pct", Math.round(pct * 10.0) / 10.0);
                tools.put(tool, one);
            }
            int spendCap = snap.spendCapInr();
            int spendUsed = snap.spendUsed24h();
            double spendPct = spendCap <= 0 ? 0.0 : Math.min(100.0, 100.0 * spendUsed / spendCap);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenantId", tid.toString());
            row.put("name", t.getName());
            row.put("sessionsToday", sessionsToday);
            row.put("threatsTotal", threatByTenant.getOrDefault(tid, 0L));
            row.put("tools", tools);
            row.put("spendUsed24hInr", spendUsed);
            row.put("spendCap24hInr", spendCap);
            row.put("spendPct", Math.round(spendPct * 10.0) / 10.0);
            out.add(row);
        }
        return out;
    }
}
