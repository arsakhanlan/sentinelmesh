"""Mocked side-effect tools. Always 'succeed' without doing the real thing.
The point is to drive Sentinel's DLP + blast-radius / approval logic, NOT to
actually send mail or move money.
"""

from __future__ import annotations

from typing import Any

from sentinelmesh_agents.tools.registry import Tool


async def _mock_email_send(args: dict[str, Any]) -> dict[str, Any]:
    return {"mock": True, "to": args.get("to"), "subject": args.get("subject"),
            "delivered": True}


async def _mock_payment_charge(args: dict[str, Any]) -> dict[str, Any]:
    return {"mock": True, "amount": args.get("amount"),
            "currency": args.get("currency"), "vendor": args.get("vendor"),
            "txn_id": "mock-txn-0001"}


async def _notes_append(args: dict[str, Any]) -> dict[str, Any]:
    return {"saved": True, "text": args.get("text", "")[:200]}


def mock_email_tool() -> Tool:
    return Tool(
        name="email.send",
        description="Send an email. (MOCKED — returns success without sending.)",
        args_schema={"to": "string", "subject": "string", "body": "string"},
        fn=_mock_email_send,
    )


def mock_payment_tool() -> Tool:
    return Tool(
        name="payments.charge",
        description="Charge an amount to a vendor. (MOCKED — returns success without charging.)",
        args_schema={"amount": "number", "currency": "string", "vendor": "string",
                      "memo": "string?"},
        fn=_mock_payment_charge,
    )


def notes_tool() -> Tool:
    return Tool(
        name="notes.append",
        description="Append a note to the agent's scratchpad. Safe side-effect for the demo.",
        args_schema={"text": "string"},
        fn=_notes_append,
    )
