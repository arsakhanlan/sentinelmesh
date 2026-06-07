#!/usr/bin/env bash
# SentinelMesh — nightly backup script.
#
# Dumps Postgres + the SkyNest SQLite + Caddy state, gzips, and writes to
# /var/backups/sentinelmesh/<date>/. Optionally syncs to off-host storage
# (B2 / S3 / rsync) if SM_BACKUP_REMOTE is set.
#
# Install with cron:
#
#   sudo install -m 0755 ops/scripts/backup.sh /usr/local/sbin/sentinelmesh-backup
#   sudo crontab -e
#   # Add:
#   0 3 * * * /usr/local/sbin/sentinelmesh-backup >> /var/log/sentinelmesh-backup.log 2>&1

set -euo pipefail

# ---- Config (override via /etc/sentinelmesh-backup.env) ---------------------
BACKUP_ROOT="${SM_BACKUP_ROOT:-/var/backups/sentinelmesh}"
KEEP_DAYS="${SM_BACKUP_KEEP_DAYS:-14}"
PG_CONTAINER="${SM_PG_CONTAINER:-sm-postgres}"
DEMO_CONTAINER="${SM_DEMO_CONTAINER:-sm-demo-site}"
PG_USER="${POSTGRES_USER:-sentinel}"
PG_DB="${POSTGRES_DB:-sentinelmesh}"

# Optional: rclone remote, e.g. "b2:my-bucket/sentinelmesh-backups"
BACKUP_REMOTE="${SM_BACKUP_REMOTE:-}"

# Optional: env file with credentials (sources POSTGRES_*, SM_BACKUP_*).
[[ -f /etc/sentinelmesh-backup.env ]] && source /etc/sentinelmesh-backup.env

# ---- Run --------------------------------------------------------------------
DATE_TAG="$(date -u +%F)"        # UTC, YYYY-MM-DD
TIME_TAG="$(date -u +%H%M%S)"
DEST="${BACKUP_ROOT}/${DATE_TAG}"
mkdir -p "${DEST}"

log() { printf '%s  %s\n' "$(date -u +%FT%TZ)" "$*"; }

log "starting backup -> ${DEST}"

# 1. Postgres logical dump.
PG_OUT="${DEST}/postgres-${TIME_TAG}.sql.gz"
log "  dumping postgres (${PG_CONTAINER})"
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD:-}" "${PG_CONTAINER}" \
    pg_dump --clean --if-exists --no-owner -U "${PG_USER}" "${PG_DB}" \
  | gzip -9 > "${PG_OUT}"
log "    wrote $(du -h "${PG_OUT}" | cut -f1) ${PG_OUT}"

# 2. SkyNest SQLite (if the demo site is running).
if docker ps --format '{{.Names}}' | grep -q "^${DEMO_CONTAINER}$"; then
    SKYNEST_OUT="${DEST}/skynest-${TIME_TAG}.db.gz"
    log "  copying skynest sqlite (${DEMO_CONTAINER})"
    docker exec "${DEMO_CONTAINER}" cat /data/skynest-bookings.db \
      | gzip -9 > "${SKYNEST_OUT}" || log "    skynest db not present, skipping"
    [[ -s "${SKYNEST_OUT}" ]] && log "    wrote $(du -h "${SKYNEST_OUT}" | cut -f1) ${SKYNEST_OUT}"
fi

# 3. Pin the Caddy data dir (TLS certs + Let's Encrypt account key).
# Worth backing up so an emergency restore on a new VM doesn't need to
# re-issue certs and risk hitting LE rate limits.
if docker volume ls -q | grep -q sentinelmesh-agents_caddy_data; then
    CADDY_OUT="${DEST}/caddy-data-${TIME_TAG}.tar.gz"
    log "  archiving caddy_data volume"
    docker run --rm \
        -v sentinelmesh-agents_caddy_data:/src:ro \
        -v "${DEST}":/dst \
        alpine sh -c "cd /src && tar -czf /dst/caddy-data-${TIME_TAG}.tar.gz ."
    log "    wrote $(du -h "${CADDY_OUT}" | cut -f1) ${CADDY_OUT}"
fi

# 4. Off-host sync, if configured.
if [[ -n "${BACKUP_REMOTE}" ]] && command -v rclone >/dev/null; then
    log "  syncing to remote: ${BACKUP_REMOTE}"
    rclone copy "${DEST}" "${BACKUP_REMOTE}/${DATE_TAG}" --quiet
    log "    sync complete"
fi

# 5. Prune old local backups.
log "  pruning local backups older than ${KEEP_DAYS} days"
find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d -mtime "+${KEEP_DAYS}" -print -exec rm -rf {} +

log "backup complete"
