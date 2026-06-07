"""FastAPI app entry point.

Boot sequence:
  1. Build LLM, Sentinel client, tool registry
  2. Compile LangGraph
  3. Stash everything in the process-scoped Runtime
  4. Tear down on shutdown (close LLM, close HTTP client, close Playwright)
"""

from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from sentinelmesh_agents.agents.graph import build_graph
from sentinelmesh_agents.api.routes import router as api_router
from sentinelmesh_agents.llm.factory import build_llm
from sentinelmesh_agents.runtime import Runtime, set_runtime
from sentinelmesh_agents.sentinel.client import SentinelClient
from sentinelmesh_agents.tools.browser import BrowserSession
from sentinelmesh_agents.tools.defaults import build_default_registry

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s [%(name)s] %(message)s",
)
log = logging.getLogger("sentinelmesh_agents")


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("SentinelMesh agents starting...")
    llm = build_llm()
    sentinel = SentinelClient()
    tools = build_default_registry()
    graph = build_graph(llm, sentinel, tools)
    set_runtime(Runtime(llm=llm, sentinel=sentinel, tools=tools, graph=graph))
    log.info("Ready (llm=%s, tools=%s)", llm.name, [t.name for t in tools.list()])
    try:
        yield
    finally:
        log.info("Shutting down...")
        try: await sentinel.close()
        except Exception: pass
        try: await llm.aclose()
        except Exception: pass
        try: await BrowserSession.shutdown()
        except Exception: pass


app = FastAPI(
    title="SentinelMesh Agents",
    version="0.1.0",
    description="Real LangGraph agents that consult SentinelMesh for every action and result.",
    lifespan=lifespan,
)

# CORS is restricted to known callers (SOC dashboard + SkyNest concierge proxy).
# Set AGENT_CORS_ORIGINS="*" to widen for local hacking — kept narrow by default
# so an arbitrary browser tab can't dispatch agent goals.
_default_cors = "http://localhost:3000,http://localhost:9000,http://127.0.0.1:3000,http://127.0.0.1:9000"
_cors_origins = [o.strip() for o in os.getenv("AGENT_CORS_ORIGINS", _default_cors).split(",") if o.strip()]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_origins,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Content-Type", "Authorization", "X-API-Key"],
    allow_credentials=False,
)

app.include_router(api_router)
