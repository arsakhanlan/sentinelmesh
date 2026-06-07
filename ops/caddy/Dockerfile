# Custom Caddy build with the rate_limit module.
# Vanilla caddy:2.X-alpine does not include mholt/caddy-ratelimit.
FROM caddy:2.8-builder-alpine AS builder

RUN xcaddy build \
    --with github.com/mholt/caddy-ratelimit

FROM caddy:2.8-alpine
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
