# Operations (reserved)

This folder is intentionally small for the hackathon build. Use it for **production-oriented** artifacts you add later, for example:

- `docker-compose.prod.yml` overrides (TLS termination, secrets from a vault, no dev API keys in env).
- One-shot deploy notes (Azure Container Apps, ECS task defs, Helm values).
- Runbooks (rotate API keys, extend Flyway, drain Redis).

If you are not shipping any of that yet, leaving only this README avoids an empty directory looking half-finished.
