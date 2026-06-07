# SentinelMesh — active policy bundle (sample)

> Source of truth for what the policy engine BLOCKs, REWRITEs, requires
> approval for, or ALLOWs. First match wins by ascending priority.
> This document is generated from the live bundle and is safe to ingest
> into Microsoft Foundry IQ.

## Bundle source (raw YAML)

```yaml
rules:
  - name: block_secret_exfil_high_blast
    priority: 5
    when: "has_secret && blast >= 0.7"
    then: BLOCK
    reason: "DLP: outbound action with secret + high blast radius"

  - name: redact_secret_low_blast
    priority: 10
    when: "has_secret && blast < 0.7"
    then: REWRITE
    reason: "DLP: redact secret before continuing low-blast action"

  - name: block_known_attack_fingerprint
    priority: 15
    when: "scores.L7 >= 0.9"
    then: BLOCK
    reason: "L7: known attack fingerprint matched"

  - name: block_capability_escalation
    priority: 20
    when: "scores.CAP >= 0.85"
    then: BLOCK
    reason: "CAP: confused-deputy / capability-escalation attempt"

  - name: block_high_severity_content
    priority: 25
    when: "scores.L2 >= 0.85 || scores.L3 >= 0.9"
    then: BLOCK
    reason: "Content Safety: critical category severity"

  - name: approval_medium_risk_outbound
    priority: 50
    when: "risk >= 0.6 && blast >= 0.4"
    then: REQUIRE_APPROVAL
    reason: "Medium risk on a non-trivial-blast action"

  - name: allow_default
    priority: 1000
    when: "true"
    then: ALLOW
    reason: "Default ALLOW after all denial rules"
```

## Compiled rules

### `block_secret_exfil_high_blast`  (priority 5)

- **Decision:** `BLOCK`
- **When:** `has_secret && blast >= 0.7`
- **Reason cited to operators:** DLP: outbound action with secret + high blast radius

### `redact_secret_low_blast`  (priority 10)

- **Decision:** `REWRITE`
- **When:** `has_secret && blast < 0.7`
- **Reason cited to operators:** DLP: redact secret before continuing low-blast action

### `block_known_attack_fingerprint`  (priority 15)

- **Decision:** `BLOCK`
- **When:** `scores.L7 >= 0.9`
- **Reason cited to operators:** L7: known attack fingerprint matched

### `block_capability_escalation`  (priority 20)

- **Decision:** `BLOCK`
- **When:** `scores.CAP >= 0.85`
- **Reason cited to operators:** CAP: confused-deputy / capability-escalation attempt

### `block_high_severity_content`  (priority 25)

- **Decision:** `BLOCK`
- **When:** `scores.L2 >= 0.85 || scores.L3 >= 0.9`
- **Reason cited to operators:** Content Safety: critical category severity

### `approval_medium_risk_outbound`  (priority 50)

- **Decision:** `REQUIRE_APPROVAL`
- **When:** `risk >= 0.6 && blast >= 0.4`
- **Reason cited to operators:** Medium risk on a non-trivial-blast action

### `allow_default`  (priority 1000)

- **Decision:** `ALLOW`
- **When:** `true`
- **Reason cited to operators:** Default ALLOW after all denial rules
