package com.sentinelmesh.security.budget;

import com.sentinelmesh.domain.model.Session;
import com.sentinelmesh.domain.service.SessionService;
import com.sentinelmesh.persistence.entity.TenantEntity;
import com.sentinelmesh.persistence.repository.TenantJpaRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session capability counters plus per-tenant rolling 24h counters.
 *
 * <p>Session caps come from the session's {@code capability_token}. Tenant caps
 * come from {@code tenants.daily_tool_caps} and {@code tenants.daily_spend_cap_inr}.
 * Both must pass — tenant rolling window is evaluated first (tighter org-wide
 * limit), then session lifetime caps.
 */
@Component
public class BudgetTracker {

    private static final long WINDOW_MS = 86_400_000L;
    private static final int MAX_TRACKED_SESSIONS = 10_000;

    public enum BudgetScope { SESSION, TENANT }

    public record Check(boolean exceeded, String tool, int used, int cap, int spendUsed, int spendCap,
                        String reason, BudgetScope scope) {}

    public record Snapshot(Map<String, ToolUsage> tools, int spendUsed, int spendCap) {
        public record ToolUsage(int used, int cap) {}
    }

    public record TenantSnapshot(Map<String, Integer> toolUsed24h, int spendUsed24h,
                                 Map<String, Integer> toolCaps, int spendCapInr) {}

    private final SessionService sessions;
    private final TenantJpaRepository tenants;
    private final Map<UUID, SessionLedger> ledgers = new ConcurrentHashMap<>();
    private final Map<UUID, TenantRollingLedger> tenantLedgers = new ConcurrentHashMap<>();

    public BudgetTracker(SessionService sessions, TenantJpaRepository tenants) {
        this.sessions = sessions;
        this.tenants = tenants;
    }

    /**
     * Atomically records this outbound tool call against session + tenant
     * rolling windows and returns whether limits would be exceeded.
     *
     * <p><b>Important:</b> when {@link Check#exceeded()} is true, no counters
     * are incremented — the call is rejected for budgeting purposes without
     * consuming a slot (fail-closed accounting).
     */
    public Check recordAndCheck(UUID sessionId, String tool, Map<String, Object> args) {
        if (sessionId == null || tool == null) {
            return new Check(false, tool, 0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, "no session", BudgetScope.SESSION);
        }
        CapabilityBudget sessionBudget = budgetFor(sessionId);
        int spendThisCall = inrAmount(tool, args);
        Session session = sessions.find(sessionId).orElse(null);
        UUID tenantId = session == null ? null : session.tenantId();
        TenantEntity tenant = (tenantId == null) ? null : tenants.findById(tenantId).orElse(null);

        SessionLedger ledger = ledgers.computeIfAbsent(sessionId, k -> new SessionLedger());
        if (ledgers.size() > MAX_TRACKED_SESSIONS) {
            ledgers.entrySet().removeIf(e -> e.getValue().total() > 0
                    && e.getValue().lastTouchMillis < System.currentTimeMillis() - 3_600_000);
        }

        Instant now = Instant.now();
        TenantRollingLedger tLed = tenant != null
                ? tenantLedgers.computeIfAbsent(tenantId, k -> new TenantRollingLedger()) : null;

        if (tLed != null) {
            synchronized (tLed) {
                tLed.pruneOlderThan(now);
                int tenantToolCap = tenantCapFor(tenant, tool);
                int tenantUsesAfter = tLed.countTool(tool) + 1;
                if (tenantToolCap != Integer.MAX_VALUE && tenantUsesAfter > tenantToolCap) {
                    return new Check(true, tool, tenantUsesAfter, tenantToolCap, tLed.sumSpend(),
                            tenantSpendCap(tenant),
                            "Tenant 24h cap exceeded for '" + tool + "' (" + tenantUsesAfter + " > "
                                    + tenantToolCap + ")",
                            BudgetScope.TENANT);
                }
                int tenantSpendCap = tenantSpendCap(tenant);
                int tenantSpendAfter = tLed.sumSpend() + spendThisCall;
                if (tenantSpendAfter > tenantSpendCap) {
                    return new Check(true, tool, 0, sessionBudget.capFor(tool), tLed.sumSpend(),
                            tenantSpendCap,
                            "Tenant 24h spend cap exceeded (" + tenantSpendAfter + " > " + tenantSpendCap + " INR)",
                            BudgetScope.TENANT);
                }

                synchronized (ledger) {
                    int usedAfter = ledger.countOf(tool) + 1;
                    int cap = sessionBudget.capFor(tool);
                    if (usedAfter > cap) {
                        return new Check(true, tool, usedAfter, cap, ledger.spend, sessionBudget.spendCapInr(),
                                "tool '" + tool + "' over session cap (" + usedAfter + " > " + cap + ")",
                                BudgetScope.SESSION);
                    }
                    int spendAfter = ledger.spend + spendThisCall;
                    if (spendAfter > sessionBudget.spendCapInr()) {
                        return new Check(true, tool, usedAfter, cap, spendAfter, sessionBudget.spendCapInr(),
                                "session spend cap exceeded (" + spendAfter + " > " + sessionBudget.spendCapInr()
                                        + " INR)",
                                BudgetScope.SESSION);
                    }
                    ledger.bump(tool);
                    ledger.bumpSpend(spendThisCall);
                    ledger.lastTouchMillis = System.currentTimeMillis();
                    tLed.pruneOlderThan(now);
                    tLed.recordTool(tool, now);
                    if (spendThisCall > 0) {
                        tLed.recordSpend(spendThisCall, now);
                    }
                    return new Check(false, tool, ledger.countOf(tool), cap, ledger.spend,
                            sessionBudget.spendCapInr(), "within budget", BudgetScope.SESSION);
                }
            }
        }

        synchronized (ledger) {
            int usedAfter = ledger.countOf(tool) + 1;
            int cap = sessionBudget.capFor(tool);
            if (usedAfter > cap) {
                return new Check(true, tool, usedAfter, cap, ledger.spend, sessionBudget.spendCapInr(),
                        "tool '" + tool + "' over session cap (" + usedAfter + " > " + cap + ")",
                        BudgetScope.SESSION);
            }
            int spendAfter = ledger.spend + spendThisCall;
            if (spendAfter > sessionBudget.spendCapInr()) {
                return new Check(true, tool, usedAfter, cap, spendAfter, sessionBudget.spendCapInr(),
                        "session spend cap exceeded (" + spendAfter + " > " + sessionBudget.spendCapInr() + " INR)",
                        BudgetScope.SESSION);
            }
            ledger.bump(tool);
            ledger.bumpSpend(spendThisCall);
            ledger.lastTouchMillis = System.currentTimeMillis();
            return new Check(false, tool, ledger.countOf(tool), cap, ledger.spend,
                    sessionBudget.spendCapInr(), "within budget", BudgetScope.SESSION);
        }
    }

    public Snapshot snapshot(UUID sessionId) {
        CapabilityBudget budget = budgetFor(sessionId);
        SessionLedger ledger = ledgers.get(sessionId);
        Map<String, Snapshot.ToolUsage> tools = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : budget.toolCaps().entrySet()) {
            int used = ledger == null ? 0 : ledger.countOf(e.getKey());
            tools.put(e.getKey(), new Snapshot.ToolUsage(used, e.getValue()));
        }
        if (ledger != null) {
            for (Map.Entry<String, Integer> e : ledger.snapshotCounts().entrySet()) {
                tools.putIfAbsent(e.getKey(), new Snapshot.ToolUsage(e.getValue(), Integer.MAX_VALUE));
            }
        }
        int spendUsed = ledger == null ? 0 : ledger.spend;
        return new Snapshot(tools, spendUsed, budget.spendCapInr());
    }

    public TenantSnapshot tenantSnapshot(UUID tenantId) {
        if (tenantId == null) {
            return new TenantSnapshot(Map.of(), 0, Map.of(), 0);
        }
        TenantEntity tenant = tenants.findById(tenantId).orElse(null);
        if (tenant == null) {
            return new TenantSnapshot(Map.of(), 0, Map.of(), 0);
        }
        Map<String, Integer> caps = new LinkedHashMap<>();
        if (tenant.getDailyToolCaps() != null) {
            for (Map.Entry<String, Object> e : tenant.getDailyToolCaps().entrySet()) {
                Integer v = CapabilityBudget.toInt(e.getValue());
                if (v != null && v >= 0) caps.put(e.getKey(), v);
            }
        }
        Instant now = Instant.now();
        TenantRollingLedger tLed = tenantLedgers.get(tenantId);
        Map<String, Integer> used = new LinkedHashMap<>();
        if (tLed != null) {
            synchronized (tLed) {
                tLed.pruneOlderThan(now);
                for (String tool : caps.keySet()) {
                    used.put(tool, tLed.countTool(tool));
                }
                for (String t : tLed.toolsWithEvents()) {
                    used.putIfAbsent(t, tLed.countTool(t));
                }
                return new TenantSnapshot(used, tLed.sumSpend(), caps, tenantSpendCap(tenant));
            }
        }
        return new TenantSnapshot(used, 0, caps, tenantSpendCap(tenant));
    }

    private static int tenantCapFor(TenantEntity tenant, String tool) {
        if (tool == null || tenant.getDailyToolCaps() == null) return Integer.MAX_VALUE;
        Object v = tenant.getDailyToolCaps().get(tool);
        Integer n = CapabilityBudget.toInt(v);
        return n == null ? Integer.MAX_VALUE : n;
    }

    private static int tenantSpendCap(TenantEntity tenant) {
        BigDecimal b = tenant.getDailySpendCapInr();
        return b == null ? Integer.MAX_VALUE : b.intValue();
    }

    private CapabilityBudget budgetFor(UUID sessionId) {
        return sessions.find(sessionId)
                .map(Session::capabilityToken)
                .map(CapabilityBudget::fromToken)
                .orElseGet(CapabilityBudget::defaults);
    }

    private static int inrAmount(String tool, Map<String, Object> args) {
        if (!"payments.charge".equals(tool) || args == null) return 0;
        Object amt = args.get("amount");
        if (amt instanceof Number n) return n.intValue();
        if (amt instanceof String s) {
            try { return (int) Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static final class SessionLedger {
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private int spend = 0;
        private volatile long lastTouchMillis = System.currentTimeMillis();

        void bump(String tool) {
            counts.merge(tool, 1, Integer::sum);
        }

        void bumpSpend(int delta) {
            spend += delta;
        }

        int countOf(String tool) { return counts.getOrDefault(tool, 0); }

        int total() {
            int t = 0; for (int v : counts.values()) t += v; return t;
        }

        Map<String, Integer> snapshotCounts() { return new LinkedHashMap<>(counts); }
    }

    private static final class TenantRollingLedger {
        private final Map<String, ArrayDeque<Long>> toolEvents = new LinkedHashMap<>();
        private final ArrayDeque<long[]> spendEvents = new ArrayDeque<>();

        void pruneOlderThan(Instant now) {
            long cutoff = now.toEpochMilli() - WINDOW_MS;
            for (ArrayDeque<Long> dq : toolEvents.values()) {
                while (!dq.isEmpty() && dq.peekFirst() < cutoff) dq.pollFirst();
            }
            while (!spendEvents.isEmpty() && spendEvents.peekFirst()[0] < cutoff) {
                spendEvents.pollFirst();
            }
        }

        int countTool(String tool) {
            ArrayDeque<Long> dq = toolEvents.get(tool);
            return dq == null ? 0 : dq.size();
        }

        int sumSpend() {
            int s = 0;
            for (long[] e : spendEvents) s += (int) e[1];
            return s;
        }

        void recordTool(String tool, Instant now) {
            toolEvents.computeIfAbsent(tool, k -> new ArrayDeque<>()).addLast(now.toEpochMilli());
        }

        void recordSpend(int inr, Instant now) {
            if (inr > 0) spendEvents.addLast(new long[] { now.toEpochMilli(), inr });
        }

        java.util.Set<String> toolsWithEvents() {
            return toolEvents.keySet();
        }
    }
}
