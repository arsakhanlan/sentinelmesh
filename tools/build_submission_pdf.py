#!/usr/bin/env python3
"""Build SentinelMesh-Submission.pdf from SentinelMesh-Submission.md (repo root).

Run from anywhere:
  python tools/build_submission_pdf.py

Requires: pip install reportlab  (or: cd sentinelmesh-agents && pip install -e ".[docs]")
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_JUSTIFY
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import (
    HRFlowable,
    Paragraph,
    Preformatted,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

ROOT = Path(__file__).resolve().parent.parent
MD_PATH = ROOT / "SentinelMesh-Submission.md"
PDF_PATH = ROOT / "SentinelMesh-Submission.pdf"


def _strip_md_bold(text: str) -> str:
    return re.sub(r"\*\*(.+?)\*\*", r"\1", text)


def _escape(text: str) -> str:
    text = _strip_md_bold(text)
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def parse_md_to_flowables(lines: list[str]) -> list:
    styles = getSampleStyleSheet()
    h1 = ParagraphStyle(
        "H1",
        parent=styles["Heading1"],
        fontSize=18,
        spaceAfter=14,
        textColor=colors.HexColor("#0f172a"),
    )
    h2 = ParagraphStyle(
        "H2",
        parent=styles["Heading2"],
        fontSize=14,
        spaceBefore=12,
        spaceAfter=8,
        textColor=colors.HexColor("#1e3a5f"),
    )
    h3 = ParagraphStyle(
        "H3",
        parent=styles["Heading3"],
        fontSize=11,
        spaceBefore=8,
        spaceAfter=6,
    )
    body = ParagraphStyle(
        "Body",
        parent=styles["Normal"],
        fontSize=10,
        leading=13,
        alignment=TA_JUSTIFY,
        spaceAfter=6,
    )
    mono = ParagraphStyle(
        "Mono",
        parent=styles["Code"],
        fontName="Courier",
        fontSize=8,
        leading=10,
        leftIndent=12,
        spaceAfter=8,
    )
    bullet = ParagraphStyle(
        "Bullet",
        parent=body,
        leftIndent=18,
        bulletIndent=8,
        spaceAfter=4,
    )

    story: list = []
    i = 0
    n = len(lines)

    while i < n:
        raw = lines[i].rstrip("\n")
        line = raw.rstrip()

        if line.strip() == "":
            i += 1
            continue

        if line.startswith("```"):
            buf: list[str] = []
            i += 1
            while i < n and not lines[i].strip().startswith("```"):
                buf.append(lines[i].rstrip("\n"))
                i += 1
            if i < n:
                i += 1
            code = _escape("\n".join(buf))
            story.append(Preformatted(code, mono))
            story.append(Spacer(1, 0.12 * inch))
            continue

        if line.startswith("# "):
            story.append(Paragraph(_escape(line[2:].strip()), h1))
            story.append(Spacer(1, 0.15 * inch))
            i += 1
            continue

        if line.startswith("## "):
            story.append(Spacer(1, 0.08 * inch))
            story.append(Paragraph(_escape(line[3:].strip()), h2))
            i += 1
            continue

        if line.startswith("### "):
            story.append(Paragraph(_escape(line[4:].strip()), h3))
            i += 1
            continue

        if line.startswith("|") and "|" in line[1:]:
            rows: list[list[str]] = []
            while i < n and lines[i].strip().startswith("|"):
                row = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                rows.append(row)
                i += 1
            if len(rows) >= 2:
                sep = rows[1]
                if all(re.match(r"^[\-\s:]+$", (c or "").strip()) for c in sep):
                    rows.pop(1)
            if rows:
                data = [[_escape(c) for c in r] for r in rows]
                t = Table(data, repeatRows=1)
                t.setStyle(
                    TableStyle(
                        [
                            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#e2e8f0")),
                            ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
                            ("FONTSIZE", (0, 0), (-1, -1), 8),
                            ("GRID", (0, 0), (-1, -1), 0.25, colors.grey),
                            ("VALIGN", (0, 0), (-1, -1), "TOP"),
                            ("LEFTPADDING", (0, 0), (-1, -1), 4),
                            ("RIGHTPADDING", (0, 0), (-1, -1), 4),
                        ]
                    )
                )
                story.append(t)
                story.append(Spacer(1, 0.12 * inch))
            continue

        if re.match(r"^[\-\*]\s+", line.strip()):
            bullets: list[str] = []
            while i < n:
                L = lines[i].strip()
                if L == "":
                    i += 1
                    break
                if not re.match(r"^[\-\*]\s+", L):
                    break
                bullets.append(re.sub(r"^[\-\*]\s+", "", L))
                i += 1
            for b in bullets:
                story.append(Paragraph(_escape(b), bullet, bulletText="•"))
            story.append(Spacer(1, 0.08 * inch))
            continue

        para: list[str] = []
        while i < n:
            L = lines[i].rstrip()
            if L.strip() == "":
                i += 1
                break
            if L.startswith("#") or L.startswith("```") or L.startswith("|"):
                break
            if re.match(r"^[\-\*]\s+", L.strip()):
                break
            para.append(L.strip())
            i += 1
        text = " ".join(para)
        if text:
            story.append(Paragraph(_escape(text), body))

    return story


def build() -> None:
    if not MD_PATH.is_file():
        print(f"Missing markdown: {MD_PATH}", file=sys.stderr)
        sys.exit(1)

    text = MD_PATH.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)

    doc = SimpleDocTemplate(
        str(PDF_PATH),
        pagesize=letter,
        rightMargin=0.75 * inch,
        leftMargin=0.75 * inch,
        topMargin=0.75 * inch,
        bottomMargin=0.75 * inch,
        title="SentinelMesh — Hackathon Submission",
        author="Mohammad Arsalan; Kashif Wahaj",
    )

    def _footer(canvas, doc_):
        canvas.saveState()
        canvas.setFont("Helvetica", 8)
        canvas.setFillColor(colors.grey)
        footer = f"Page {doc_.page}"
        canvas.drawString(0.75 * inch, 0.5 * inch, footer)
        canvas.restoreState()

    story = parse_md_to_flowables(lines)
    story.insert(0, Spacer(1, 0.1 * inch))
    story.insert(1, HRFlowable(width=6.5 * inch, thickness=0.5, color=colors.HexColor("#cbd5e1")))
    story.insert(2, Spacer(1, 0.15 * inch))

    doc.build(story, onFirstPage=_footer, onLaterPages=_footer)
    print(f"Wrote {PDF_PATH} ({PDF_PATH.stat().st_size} bytes)")


if __name__ == "__main__":
    build()
