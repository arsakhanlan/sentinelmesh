"""Builds the default tool set the agent has access to."""

from __future__ import annotations

from sentinelmesh_agents.tools.booking_tool import bookings_create_tool
from sentinelmesh_agents.tools.browser import browser_goto_tool
from sentinelmesh_agents.tools.http_tool import http_get_tool
from sentinelmesh_agents.tools.mock_tools import (
    mock_email_tool, mock_payment_tool, notes_tool,
)
from sentinelmesh_agents.tools.registry import ToolRegistry


def build_default_registry() -> ToolRegistry:
    reg = ToolRegistry()
    reg.register(browser_goto_tool())
    reg.register(http_get_tool())
    reg.register(bookings_create_tool())
    reg.register(mock_email_tool())
    reg.register(mock_payment_tool())
    reg.register(notes_tool())
    return reg
