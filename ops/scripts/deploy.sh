#!/usr/bin/env bash
# =============================================================================
# SentinelMesh — single-VM deploy script
# =============================================================================
#
# Stand up the full SentinelMesh stack on a fresh Linux VM behind Caddy +
# Let's Encrypt. Idempotent — re-running it just rebuilds and restarts.
#
# REQUIREMENTS
#   * Ubuntu 22.04 / 24.04 / Debian 12 / Rocky 9 (any modern systemd distro
#     with apt or dnf is fine; this script auto-detects the package manager)
#   * 4 GB RAM, 2+ vCPU, 40 GB disk minimum (see DEPLOYMENT.md for tested VMs)
#   * sudo / root
#   * Two A records pointing at this VM's public IP:
#       sentinel.<your-domain>     →  <vm-ip>
#       skynest.<your-domain>      →  <vm-ip>
#
# USAGE
#   First time, on the VM:
#       git clone https://github.com/<your-fork>/SentinelMesh.git
#       cd SentinelMesh
#       sudo bash ops/scripts/deploy.sh
#
#   Subcommands (after first deploy):
#       sudo bash ops/scripts/deploy.sh restart      # restart all containers
#       sudo bash ops/scripts/deploy.sh upgrade      # git pull + rebuild + restart
#       sudo bash ops/scripts/deploy.sh logs [svc]   # tail logs (all or one service)
#       sudo bash ops/scripts/deploy.sh status       # show container status
#       sudo bash ops/scripts/deploy.sh backup       # run the nightly backup once
#       sudo bash ops/scripts/deploy.sh stop         # stop all containers
#       sudo bash ops/scripts/deploy.sh down         # stop AND remove containers
#       sudo bash ops/scripts/deploy.sh test         # smoke test both URLs
#
# WHAT YOU MUST EDIT BY HAND
#   Exactly one file: /etc/sentinelmesh.env
#   The script writes a template there on first run. The four fields you
#   MUST fill in (everything else is auto-generated):
#
#       SM_DOMAIN=your-domain.example
#       SM_ACME_EMAIL=you@your-domain.example
#       OPENAI_API_KEY=sk-deepseek-...
#       SM_ADMIN_PLAINTEXT_PASSWORD=pick-a-strong-admin-password
#
#   On first run the script will: (a) generate every other secret, (b) open
#   an editor on /etc/sentinelmesh.env, (c) wait for you to save+exit, (d)
#   validate the file, then continue.
# =============================================================================

set -euo pipefail

# -------------------------- Config & defaults --------------------------------

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${SM_ENV_FILE:-/etc/sentinelmesh.env}"
ENV_TEMPLATE="${REPO_ROOT}/ops/sentinelmesh.env.example"
COMPOSE_BASE="${REPO_ROOT}/sentinelmesh-agents/docker-compose.yml"
COMPOSE_PROD="${REPO_ROOT}/sentinelmesh-agents/docker-compose.prod.yml"
CADDYFILE="${REPO_ROOT}/ops/caddy/Caddyfile"
BACKUP_SCRIPT="${REPO_ROOT}/ops/scripts/backup.sh"

# Compose project name — keeps container/volume names predictable on the VM.
COMPOSE_PROJECT="sentinelmesh"

# Color helpers (skip if not a TTY).
if [[ -t 1 ]]; then
    C_GREEN=$'\033[0;32m'; C_BLUE=$'\033[0;34m'; C_YELLOW=$'\033[0;33m'
    C_RED=$'\033[0;31m'; C_BOLD=$'\033[1m'; C_RESET=$'\033[0m'
else
    C_GREEN=""; C_BLUE=""; C_YELLOW=""; C_RED=""; C_BOLD=""; C_RESET=""
fi

step() { printf '\n%s==>%s %s%s%s\n' "$C_BLUE" "$C_RESET" "$C_BOLD" "$*" "$C_RESET"; }
ok()   { printf '   %s✓%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '   %s!%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
err()  { printf '   %s✗%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; }
die()  { err "$*"; exit 1; }

# -------------------------- Utility checks -----------------------------------

require_root() {
    [[ $EUID -eq 0 ]] || die "Please run with sudo: sudo bash $0 $*"
}

detect_pkg_manager() {
    if command -v apt-get >/dev/null 2>&1; then echo apt
    elif command -v dnf  >/dev/null 2>&1; then echo dnf
    elif command -v yum  >/dev/null 2>&1; then echo yum
    else die "Unsupported distro: no apt-get / dnf / yum found"
    fi
}

DOCKER_COMPOSE() {
    docker compose \
        --project-name "$COMPOSE_PROJECT" \
        --env-file "$ENV_FILE" \
        -f "$COMPOSE_BASE" \
        -f "$COMPOSE_PROD" \
        "$@"
}

# -------------------------- Stage: install prereqs ---------------------------

install_prereqs() {
    step "Installing prerequisites"

    local pm; pm="$(detect_pkg_manager)"

    case "$pm" in
        apt)
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -qq
            apt-get install -y -qq curl ca-certificates gnupg lsb-release \
                git openssl ufw jq dnsutils
            # Docker engine + compose plugin (Ubuntu/Debian official repo)
            if ! command -v docker >/dev/null 2>&1; then
                install -m 0755 -d /etc/apt/keyrings
                curl -fsSL https://download.docker.com/linux/$(. /etc/os-release; echo "$ID")/gpg \
                    | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
                chmod a+r /etc/apt/keyrings/docker.gpg
                echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/$(. /etc/os-release; echo "$ID") \
$(. /etc/os-release; echo "$VERSION_CODENAME") stable" \
                    > /etc/apt/sources.list.d/docker.list
                apt-get update -qq
                apt-get install -y -qq docker-ce docker-ce-cli containerd.io \
                    docker-buildx-plugin docker-compose-plugin
                systemctl enable --now docker
                ok "installed docker engine + compose plugin"
            else
                ok "docker already installed"
            fi
            ;;
        dnf|yum)
            $pm install -y -q curl ca-certificates git openssl jq bind-utils firewalld
            if ! command -v docker >/dev/null 2>&1; then
                $pm install -y -q dnf-plugins-core
                $pm config-manager --add-repo \
                    https://download.docker.com/linux/centos/docker-ce.repo
                $pm install -y -q docker-ce docker-ce-cli containerd.io \
                    docker-buildx-plugin docker-compose-plugin
                systemctl enable --now docker
                ok "installed docker engine + compose plugin"
            else
                ok "docker already installed"
            fi
            ;;
    esac

    # Sanity-check compose version: we need v2.24+ for the !reset YAML tag.
    local compose_v
    compose_v="$(docker compose version --short 2>/dev/null || echo 0)"
    if ! printf '%s\n' "$compose_v" | awk -F. '
        { v = ($1+0)*10000 + ($2+0)*100 + ($3+0); exit (v >= 22400 ? 0 : 1) }'; then
        warn "docker compose version is $compose_v; need v2.24+ for !reset support"
        warn "trying to upgrade…"
        case "$pm" in
            apt) apt-get install -y -qq --only-upgrade docker-compose-plugin ;;
            dnf|yum) $pm upgrade -y -q docker-compose-plugin ;;
        esac
        compose_v="$(docker compose version --short 2>/dev/null || echo 0)"
    fi
    ok "docker compose v${compose_v}"
}

# -------------------------- Stage: configure firewall ------------------------

configure_firewall() {
    step "Configuring firewall (22, 80, 443)"
    if command -v ufw >/dev/null 2>&1; then
        ufw allow 22/tcp     >/dev/null
        ufw allow 80/tcp     >/dev/null
        ufw allow 443/tcp    >/dev/null
        ufw allow 443/udp    >/dev/null   # HTTP/3
        if ! ufw status | grep -q "Status: active"; then
            warn "ufw is installed but inactive; enabling now"
            yes | ufw enable >/dev/null || true
        fi
        ok "ufw active; 22, 80, 443 open"
    elif command -v firewall-cmd >/dev/null 2>&1; then
        systemctl enable --now firewalld
        for p in ssh http https; do firewall-cmd --add-service="$p" --permanent >/dev/null; done
        firewall-cmd --add-port=443/udp --permanent >/dev/null
        firewall-cmd --reload >/dev/null
        ok "firewalld active; ssh, http, https open"
    else
        warn "no firewall tool found; skipping"
    fi
}

# -------------------------- Stage: env file ----------------------------------

write_env_template() {
    [[ -f "$ENV_FILE" ]] && return 0

    step "Generating /etc/sentinelmesh.env (first-time setup)"
    [[ -f "$ENV_TEMPLATE" ]] || die "Template missing: $ENV_TEMPLATE"

    # Generate strong secrets up-front so the user only has to fill in domain,
    # email, OpenAI key, and admin password.
    local pg_pw api_k acme_k pub_k jwt_s
    pg_pw="$(openssl rand -hex 32)"
    api_k="$(openssl rand -hex 32)"
    acme_k="$(openssl rand -hex 32)"
    pub_k="$(openssl rand -hex 32)"
    jwt_s="$(openssl rand -hex 64)"

    cp "$ENV_TEMPLATE" "$ENV_FILE"
    chmod 600 "$ENV_FILE"

    # Replace the REPLACE_ME tokens for the auto-generated secrets.
    sed -i \
        -e "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=${pg_pw}|" \
        -e "s|^SENTINELMESH_API_KEY=.*|SENTINELMESH_API_KEY=${api_k}|" \
        -e "s|^SENTINELMESH_ACME_API_KEY=.*|SENTINELMESH_ACME_API_KEY=${acme_k}|" \
        -e "s|^SENTINELMESH_PUBLIC_DEMO_KEY=.*|SENTINELMESH_PUBLIC_DEMO_KEY=${pub_k}|" \
        -e "s|^SENTINELMESH_JWT_SECRET=.*|SENTINELMESH_JWT_SECRET=${jwt_s}|" \
        "$ENV_FILE"

    # Append a marker the user can search for (we re-prompt on this).
    cat >> "$ENV_FILE" <<EOF

# ---- ADMIN PASSWORD ----
# Plain-text only — the deploy script bcrypts it into SM_ADMIN_BASIC_AUTH
# the next time it runs. Pick something strong; this protects /admin/* and
# /actuator/* on https://sentinel.<your-domain>.
SM_ADMIN_PLAINTEXT_PASSWORD=REPLACE_ME_pick_a_strong_admin_password
EOF

    ok "wrote $ENV_FILE (chmod 600)"
    cat <<EOF

  ${C_BOLD}Now edit ${ENV_FILE} and fill in the four fields below:${C_RESET}

      SM_DOMAIN=your-domain.example
      SM_ACME_EMAIL=you@your-domain.example
      OPENAI_API_KEY=sk-deepseek-...
      SM_ADMIN_PLAINTEXT_PASSWORD=pick-a-strong-admin-password

  Everything else (POSTGRES_PASSWORD, SENTINELMESH_*_KEY, JWT_SECRET) is
  already auto-generated in the file.

  ${C_YELLOW}Press Enter to open the file in nano, or Ctrl-C to edit it manually
  and re-run this script when you're done.${C_RESET}
EOF
    read -r _ || true
    "${EDITOR:-nano}" "$ENV_FILE"
}

bcrypt_admin_password() {
    # Generate the SM_ADMIN_BASIC_AUTH hash from SM_ADMIN_PLAINTEXT_PASSWORD,
    # if the user filled the plaintext field. Idempotent.
    local plaintext
    plaintext="$(grep -E '^SM_ADMIN_PLAINTEXT_PASSWORD=' "$ENV_FILE" | cut -d= -f2- || true)"
    [[ -z "$plaintext" || "$plaintext" =~ ^REPLACE_ME ]] && return 0

    step "Hashing admin password with bcrypt"
    local hash
    hash="$(docker run --rm caddy:2.8-alpine \
        caddy hash-password --plaintext "$plaintext" 2>/dev/null \
        | tr -d '\r\n')"
    [[ -n "$hash" ]] || die "caddy hash-password produced empty output"

    # Replace SM_ADMIN_BASIC_AUTH and clear the plaintext line.
    sed -i \
        -e "s|^SM_ADMIN_BASIC_AUTH=.*|SM_ADMIN_BASIC_AUTH=${hash}|" \
        -e "s|^SM_ADMIN_PLAINTEXT_PASSWORD=.*|SM_ADMIN_PLAINTEXT_PASSWORD=|" \
        "$ENV_FILE"
    ok "SM_ADMIN_BASIC_AUTH set; plaintext cleared from env file"
}

# Extract one value from /etc/sentinelmesh.env without sourcing the file.
# Sourcing is dangerous: bcrypt hashes contain `$` chars that bash would try
# to expand at parse time, corrupting SM_ADMIN_BASIC_AUTH.
get_env() {
    local key="$1"
    grep -E "^${key}=" "$ENV_FILE" | head -n1 | cut -d= -f2- || true
}

validate_env() {
    step "Validating $ENV_FILE"

    local required=(SM_DOMAIN SM_ACME_EMAIL POSTGRES_USER POSTGRES_PASSWORD
                    SENTINELMESH_API_KEY SENTINELMESH_ACME_API_KEY
                    SENTINELMESH_PUBLIC_DEMO_KEY SENTINELMESH_JWT_SECRET
                    OPENAI_API_KEY SM_ADMIN_BASIC_AUTH
                    SENTINELMESH_CORS_ALLOWED_ORIGINS)

    local missing=()
    local v val
    for v in "${required[@]}"; do
        val="$(get_env "$v")"
        if [[ -z "$val" \
              || "$val" =~ ^REPLACE_ME \
              || "$val" =~ your-domain\.example \
              || "$val" =~ ^sk-deepseek-REPLACE ]]; then
            missing+=("$v")
        fi
    done
    if (( ${#missing[@]} > 0 )); then
        err "the following env vars are still placeholders or empty:"
        for m in "${missing[@]}"; do err "    $m"; done
        die "edit $ENV_FILE and re-run this script"
    fi

    # Pin two values into our script env for later stages (avoids re-grepping).
    # We export rather than `source` to dodge `$`-expansion bugs in the bcrypt
    # hash and other special-char fields.
    export SM_DOMAIN
    SM_DOMAIN="$(get_env SM_DOMAIN)"
    export SENTINELMESH_PUBLIC_DEMO_KEY
    SENTINELMESH_PUBLIC_DEMO_KEY="$(get_env SENTINELMESH_PUBLIC_DEMO_KEY)"

    # Auto-update CORS allow-list if the user changed SM_DOMAIN but left the
    # old comma-list in place from a prior run.
    local desired current
    desired="https://sentinel.${SM_DOMAIN},https://skynest.${SM_DOMAIN}"
    current="$(get_env SENTINELMESH_CORS_ALLOWED_ORIGINS)"
    if [[ "$current" != "$desired" ]]; then
        sed -i "s|^SENTINELMESH_CORS_ALLOWED_ORIGINS=.*|SENTINELMESH_CORS_ALLOWED_ORIGINS=${desired}|" "$ENV_FILE"
        ok "auto-updated SENTINELMESH_CORS_ALLOWED_ORIGINS to ${desired}"
    fi

    ok "env file is complete"
}

verify_dns() {
    step "Verifying DNS for sentinel.${SM_DOMAIN} and skynest.${SM_DOMAIN}"

    local public_ip
    public_ip="$(curl -fsSL --max-time 5 https://api.ipify.org || true)"
    [[ -n "$public_ip" ]] || warn "could not detect public IP (api.ipify.org unreachable)"
    [[ -n "$public_ip" ]] && ok "this VM's public IP: $public_ip"

    local fqdn problems=0
    for sub in sentinel skynest; do
        fqdn="${sub}.${SM_DOMAIN}"
        local resolved
        resolved="$(getent hosts "$fqdn" | awk '{print $1}' | head -n1 || true)"
        if [[ -z "$resolved" ]]; then
            err "$fqdn does NOT resolve. Add an A record pointing to ${public_ip:-this-VM-IP}."
            problems=1
        elif [[ -n "$public_ip" && "$resolved" != "$public_ip" ]]; then
            warn "$fqdn -> $resolved (this VM is $public_ip; DNS may not have propagated yet)"
        else
            ok "$fqdn -> $resolved"
        fi
    done

    if (( problems )); then
        cat <<EOF

  ${C_BOLD}DNS not configured.${C_RESET} Add A records at your registrar:

      sentinel.${SM_DOMAIN}    A    ${public_ip:-<this-VM-public-IP>}
      skynest.${SM_DOMAIN}     A    ${public_ip:-<this-VM-public-IP>}

  Then re-run this script. (Caddy will fail to obtain TLS certs without DNS.)

EOF
        die "DNS prerequisites unmet"
    fi
}

# -------------------------- Stage: build & start -----------------------------

build_and_start() {
    step "Building images and starting the stack"
    cd "$REPO_ROOT"

    # We pass the env file via --env-file to compose; compose itself will
    # substitute ${VAR} in the YAML. Build args are read from the compose
    # file directly (frontend's NEXT_PUBLIC_*).
    DOCKER_COMPOSE pull --quiet 2>/dev/null || true   # cached images, fine if it fails
    DOCKER_COMPOSE build --pull
    DOCKER_COMPOSE up -d
    ok "containers started"
}

wait_for_certs() {
    step "Waiting for Caddy to obtain Let's Encrypt certificates (up to 3 minutes)"

    local sub fqdn ok_subs=0 deadline=$(( $(date +%s) + 180 ))
    while [[ $(date +%s) -lt $deadline ]]; do
        ok_subs=0
        for sub in sentinel skynest; do
            fqdn="${sub}.${SM_DOMAIN}"
            if curl -fsSL --max-time 5 -o /dev/null \
                "https://${fqdn}/" --resolve "${fqdn}:443:127.0.0.1"; then
                ok_subs=$(( ok_subs + 1 ))
            fi
        done
        if (( ok_subs == 2 )); then
            ok "TLS certificates issued for both subdomains"
            return 0
        fi
        printf '   …waiting (%s/%s up)\n' "$ok_subs" 2
        sleep 10
    done

    warn "Caddy hasn't issued certs after 3 minutes; check 'docker compose logs caddy'"
    return 1
}

smoke_test() {
    step "Smoke testing"

    local sub fqdn problems=0
    for sub in sentinel skynest; do
        fqdn="${sub}.${SM_DOMAIN}"
        if curl -fsSL --max-time 10 -o /dev/null "https://${fqdn}/"; then
            ok "https://${fqdn}/ → 200"
        else
            err "https://${fqdn}/ — failed"; problems=1
        fi
    done

    if curl -fsSL --max-time 10 -H "X-API-Key: ${SENTINELMESH_PUBLIC_DEMO_KEY}" \
        -o /dev/null "https://sentinel.${SM_DOMAIN}/api/v1/metrics/summary"; then
        ok "backend API reachable through proxy"
    else
        warn "backend API smoke test failed (the SOC dashboard may still work — check logs)"
        problems=1
    fi

    if curl -fsSL --max-time 10 -o /dev/null "https://skynest.${SM_DOMAIN}/health"; then
        ok "demo-site /health reachable"
    else
        warn "demo-site /health failed"; problems=1
    fi

    return $problems
}

print_next_steps() {
    cat <<EOF

${C_GREEN}${C_BOLD}=== SentinelMesh is live ===${C_RESET}

  SOC dashboard:   ${C_BOLD}https://sentinel.${SM_DOMAIN}${C_RESET}
  SkyNest demo:    ${C_BOLD}https://skynest.${SM_DOMAIN}${C_RESET}
  Admin (auth):    ${C_BOLD}https://sentinel.${SM_DOMAIN}/admin/${C_RESET}    (basic-auth: admin / <password>)

  Subcommands (run from any directory inside the repo):

      sudo bash ops/scripts/deploy.sh status     # container health
      sudo bash ops/scripts/deploy.sh logs       # tail all logs
      sudo bash ops/scripts/deploy.sh logs caddy # tail one service
      sudo bash ops/scripts/deploy.sh restart    # restart everything
      sudo bash ops/scripts/deploy.sh upgrade    # git pull + rebuild + restart
      sudo bash ops/scripts/deploy.sh backup     # run nightly backup once
      sudo bash ops/scripts/deploy.sh test       # smoke test

  Optional next steps:

  1. ${C_BOLD}Set up the nightly backup cron${C_RESET}
       sudo install -m 0755 ops/scripts/backup.sh /usr/local/sbin/sentinelmesh-backup
       echo '0 3 * * * /usr/local/sbin/sentinelmesh-backup >> /var/log/sentinelmesh-backup.log 2>&1' \\
           | sudo crontab -

  2. ${C_BOLD}Add an UptimeRobot monitor${C_RESET} for each subdomain (free, 5-monitor tier).

  3. ${C_BOLD}Tighten the OpenAI / DeepSeek spend cap${C_RESET}
       Set a \$5–10 / month hard cap in your provider dashboard. Caddy
       rate-limits at 6 goals/min/IP, but a hard cap is the seatbelt.

EOF
}

# -------------------------- Subcommands --------------------------------------

cmd_deploy() {
    require_root
    install_prereqs
    configure_firewall
    write_env_template
    bcrypt_admin_password
    validate_env
    verify_dns
    build_and_start
    wait_for_certs || true   # certs sometimes take longer; smoke-test will tell us
    smoke_test || warn "smoke test had failures; investigate with 'deploy.sh logs'"
    print_next_steps
}

cmd_restart() {
    require_root
    DOCKER_COMPOSE restart
    ok "restarted"
}

cmd_upgrade() {
    require_root
    step "Pulling latest source"
    cd "$REPO_ROOT"
    git pull --ff-only
    step "Rebuilding and restarting"
    DOCKER_COMPOSE build --pull
    DOCKER_COMPOSE up -d
    ok "upgrade complete"
    smoke_test || warn "smoke test had failures; investigate with 'deploy.sh logs'"
}

cmd_logs() {
    local svc="${1:-}"
    if [[ -n "$svc" ]]; then DOCKER_COMPOSE logs -f --tail=100 "$svc"
    else DOCKER_COMPOSE logs -f --tail=50
    fi
}

cmd_status() { DOCKER_COMPOSE ps; }

cmd_backup() {
    require_root
    [[ -x "$BACKUP_SCRIPT" ]] || die "backup script missing or not executable: $BACKUP_SCRIPT"
    POSTGRES_USER="$(grep -E '^POSTGRES_USER=' "$ENV_FILE" | cut -d= -f2-)" \
    POSTGRES_PASSWORD="$(grep -E '^POSTGRES_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)" \
        bash "$BACKUP_SCRIPT"
}

cmd_stop() { require_root; DOCKER_COMPOSE stop; ok "stopped"; }
cmd_down() { require_root; DOCKER_COMPOSE down; ok "containers removed (volumes preserved)"; }

cmd_test() {
    [[ -f "$ENV_FILE" ]] || die "$ENV_FILE not found; run a full deploy first"
    export SM_DOMAIN
    SM_DOMAIN="$(get_env SM_DOMAIN)"
    export SENTINELMESH_PUBLIC_DEMO_KEY
    SENTINELMESH_PUBLIC_DEMO_KEY="$(get_env SENTINELMESH_PUBLIC_DEMO_KEY)"
    smoke_test
}

# -------------------------- Entry point --------------------------------------

main() {
    case "${1:-deploy}" in
        deploy)  cmd_deploy ;;
        restart) cmd_restart ;;
        upgrade) cmd_upgrade ;;
        logs)    cmd_logs "${2:-}" ;;
        status)  cmd_status ;;
        backup)  cmd_backup ;;
        stop)    cmd_stop ;;
        down)    cmd_down ;;
        test)    cmd_test ;;
        -h|--help|help)
            # Print only the leading comment block (everything until the first
            # blank line that follows the banner) — that's our usage docs.
            awk '
                /^# =====/ && !seen { seen=1; next }
                seen && /^[^#]/    { exit }
                seen               { sub(/^# ?/,""); print }
            ' "$0"
            ;;
        *) die "unknown subcommand: $1 (run with --help for usage)" ;;
    esac
}

main "$@"
