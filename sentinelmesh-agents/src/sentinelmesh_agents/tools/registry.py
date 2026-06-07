"""Tool registry. A tool is anything an agent can call. Each tool has a name,
a JSON-schema-ish args description, and an async function that returns a dict.

Tool implementations should NOT call Sentinel themselves — the executor node
wraps every call with `sentinel.inspect_outbound` and `inspect_inbound`. This
keeps the security boundary in exactly one place.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Awaitable, Callable

ToolFn = Callable[[dict[str, Any]], Awaitable[dict[str, Any]]]


@dataclass
class Tool:
    name: str
    description: str
    args_schema: dict[str, Any]
    fn: ToolFn


class ToolRegistry:
    def __init__(self) -> None:
        self._by_name: dict[str, Tool] = {}

    def register(self, tool: Tool) -> None:
        self._by_name[tool.name] = tool

    def get(self, name: str) -> Tool:
        if name not in self._by_name:
            raise KeyError(f"Unknown tool: {name}")
        return self._by_name[name]

    def list(self) -> list[Tool]:
        return list(self._by_name.values())

    def describe_for_prompt(self) -> str:
        lines = []
        for t in self.list():
            lines.append(f"- {t.name}({_schema(t.args_schema)}): {t.description}")
        return "\n".join(lines)


def _schema(s: dict[str, Any]) -> str:
    return ", ".join(f"{k}: {v}" for k, v in s.items())
