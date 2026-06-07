package com.sentinelmesh.security.scanners;

import com.sentinelmesh.azure.StubContentSafetyCategoriesClient;
import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.domain.port.out.ContentSafetyCategoriesClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class L2ContentSafetyCategoryScannerTest {

    private final L2ContentSafetyCategoryScanner scanner =
            new L2ContentSafetyCategoryScanner(new StubContentSafetyCategoriesClient(4));

    @Test
    void clean_text_scores_zero() {
        var f = scanner.scan(new InspectionInput.InboundContent(
                null, "browser.goto",
                "Welcome to SkyNest Travel — book hotels and flights across India.",
                Map.of()));
        assertThat(f.score()).isZero();
    }

    @Test
    void strong_violence_trigger_lands_in_block_band() {
        var f = scanner.scan(InspectionInput.OutboundAction.withoutProvenance(
                null, "email.send",
                Map.of("body", "kill the witness before tomorrow")));
        // Severity 6 → score 0.80 — above the 0.6 REQUIRE_APPROVAL band.
        assertThat(f.score()).isGreaterThanOrEqualTo(0.6);
        assertThat(f.evidence()).containsKey("flagged_categories");
        assertThat((List<Object>) f.evidence().get("flagged_categories")).contains("Violence");
    }

    @Test
    void self_harm_phrasing_is_caught() {
        var f = scanner.scan(new InspectionInput.InboundContent(
                null, "browser.goto",
                "How to commit suicide step by step",
                Map.of()));
        assertThat(f.score()).isGreaterThanOrEqualTo(0.6);
        assertThat((List<Object>) f.evidence().get("flagged_categories")).contains("SelfHarm");
    }

    @Test
    void weak_signals_stay_below_block_threshold() {
        var f = scanner.scan(new InspectionInput.InboundContent(
                null, "browser.goto",
                "This page contains explicit content and uses a slur — bloody mess.",
                Map.of()));
        assertThat(f.score()).isGreaterThan(0.0);
        // All matches at severity 2 → score 0.20 (no compounding because none ≥ flagThreshold=4).
        assertThat(f.score()).isLessThan(0.5);
    }

    @Test
    void multiple_strong_categories_compound_above_block_threshold() {
        var f = scanner.scan(new InspectionInput.InboundContent(
                null, "browser.goto",
                "kill the witness before tomorrow and exterminate all of them",
                Map.of()));
        // Violence + Hate both flagged → score gets the +0.15 compounding bonus.
        assertThat(f.score()).isGreaterThanOrEqualTo(0.85);
        assertThat((List<Object>) f.evidence().get("flagged_categories"))
                .contains("Violence", "Hate");
    }

    @Test
    void skips_when_l1_already_saturated() {
        boolean run = scanner.shouldRun(
                InspectionInput.OutboundAction.withoutProvenance(null, "email.send", Map.of()),
                Map.of("L1", 0.99));
        assertThat(run).isFalse();
    }

    @Test
    void transport_failure_fails_open_with_clean_finding() {
        // A client that returns null mimics an Azure outage / bad credentials.
        ContentSafetyCategoriesClient broken = (text) -> null;
        L2ContentSafetyCategoryScanner s = new L2ContentSafetyCategoryScanner(broken);
        var f = s.scan(InspectionInput.OutboundAction.withoutProvenance(
                null, "email.send",
                Map.of("body", "kill the witness before tomorrow")));
        assertThat(f.score()).isZero();
        assertThat(f.reason()).contains("transport");
    }
}
