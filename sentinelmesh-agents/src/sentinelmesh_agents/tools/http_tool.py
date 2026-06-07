"""Plain HTTP fetch tool. Demonstrates Sentinel scanning JSON / text responses
without spinning up a full browser context.

SSRF defense-in-depth: even though Sentinel inspects every outbound tool call
before it runs, this tool independently rejects URLs that target the cloud
metadata service, loopback, link-local, or RFC1918 ranges. A vulnerability in
the policy bundle (or a clever prompt-injection payload) shouldn't be all that
stands between the agent and an internal admin endpoint.
"""

from __future__ import annotations

import ipaddress
import logging
import socket
from typing import Any
from urllib.parse import urlparse

import httpx

from sentinelmesh_agents.config import get_settings
from sentinelmesh_agents.tools.registry import Tool

log = logging.getLogger(__name__)


_BLOCKED_HOSTS = {"metadata.google.internal", "metadata.goog"}


def _demo_site_hosts() -> set[str]:
    """Hosts that bypass the private-IP guard. Only the configured demo site —
    in Docker it lives on a private bridge so we can't otherwise reach it."""
    try:
        host = urlparse(get_settings().demo_site_base_url).hostname
        return {host.lower()} if host else set()
    except Exception:  # noqa: BLE001 — best-effort; tighter to fail closed
        return set()


class SsrfBlocked(ValueError):
    """Raised when a URL is rejected by the SSRF guard."""


def _validate_url(raw: str) -> str:
    """Reject schemes other than http/https and hostnames that resolve to a
    non-routable address (loopback, link-local, private). Returns the canonical
    URL string on success; raises ``SsrfBlocked`` otherwise."""
    if not isinstance(raw, str) or not raw:
        raise SsrfBlocked("url must be a non-empty string")
    parsed = urlparse(raw)
    if parsed.scheme not in ("http", "https"):
        raise SsrfBlocked(f"scheme not allowed: {parsed.scheme!r}")
    host = (parsed.hostname or "").lower()
    if not host:
        raise SsrfBlocked("missing host")
    if host in _BLOCKED_HOSTS:
        raise SsrfBlocked(f"host blocked: {host}")
    if host in _demo_site_hosts():
        return raw  # demo site explicitly allow-listed
    # Resolve every A/AAAA record — guards against rebinding tricks like
    # `127.0.0.1.nip.io` and against names that resolve to multiple addresses.
    try:
        infos = socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80),
                                    proto=socket.IPPROTO_TCP)
    except socket.gaierror as e:
        raise SsrfBlocked(f"DNS resolution failed: {e}")
    for info in infos:
        addr = info[4][0]
        try:
            ip = ipaddress.ip_address(addr)
        except ValueError:
            continue
        if ip.is_loopback or ip.is_private or ip.is_link_local \
                or ip.is_multicast or ip.is_reserved or ip.is_unspecified:
            raise SsrfBlocked(f"host resolves to non-routable address: {ip}")
    return raw


async def _http_get(args: dict[str, Any]) -> dict[str, Any]:
    url = _validate_url(args.get("url", ""))
    async with httpx.AsyncClient(timeout=10.0, follow_redirects=False) as c:
        r = await c.get(url)
        body = r.text[:4000]
        log.info("http.get %s → %d (%d chars)", url, r.status_code, len(body))
        return {"url": url, "status": r.status_code, "body": body,
                "content_type": r.headers.get("content-type", "")}


def http_get_tool() -> Tool:
    return Tool(
        name="http.get",
        description="GET a URL and return the body (truncated to 4000 chars). "
                    "Only http(s); private / loopback / link-local addresses are rejected.",
        args_schema={"url": "string"},
        fn=_http_get,
    )
