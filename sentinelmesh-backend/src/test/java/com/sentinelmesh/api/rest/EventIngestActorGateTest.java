package com.sentinelmesh.api.rest;

import com.sentinelmesh.api.rest.EventIngestActorGate.EventIngestAllowlist;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventIngestActorGateTest {

    @Test
    void star_means_permissive() {
        EventIngestAllowlist rules = EventIngestAllowlist.parse("*");
        assertThat(rules.permissive()).isTrue();
        assertThat(rules.validateActor("any-one")).isEqualTo("any-one");
    }

    @Test
    void allowlisted_actor_passes_with_original_casing() {
        EventIngestAllowlist rules = EventIngestAllowlist.parse("planner,Executor");
        assertThat(rules.validateActor("Planner")).isEqualTo("Planner");
    }

    @Test
    void unknown_actor_returns_null() {
        EventIngestAllowlist rules = EventIngestAllowlist.parse("planner,executor");
        assertThat(rules.validateActor("FakeExecutor")).isNull();
    }

    @Test
    void blank_actor_throws() {
        EventIngestAllowlist rules = EventIngestAllowlist.parse("planner");
        assertThatThrownBy(() -> rules.validateActor("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
