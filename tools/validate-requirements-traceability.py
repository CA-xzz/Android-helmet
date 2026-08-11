#!/usr/bin/env python3
"""Validate the manual 5.1-5.6 requirements traceability matrix."""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DOCUMENT = PROJECT_ROOT / "docs" / "REQUIREMENTS_TRACEABILITY.md"
EXPECTED_COLUMNS = (
    "REQ",
    "条款",
    "验收指标",
    "设计文档",
    "实现文件",
    "自动测试",
    "开发板或硬件证据",
    "后台证据",
    "状态",
    "阻塞项",
)
EXPECTED_REQUIREMENTS = tuple(
    f"REQ-{section}-{index:02d}"
    for section, count in (
        ("5.1", 7),
        ("5.2", 6),
        ("5.3", 6),
        ("5.4", 4),
        ("5.5", 4),
        ("5.6", 4),
    )
    for index in range(1, count + 1)
)
VALID_STATUSES = {"未实现", "软件完成待实机", "已完成"}
REQUIRED_BROWSER_EVIDENCE = {
    "REQ-5.1-06": Path("docs/verification/stage-7/dashboard-access-center-regression.txt"),
    "REQ-5.2-04": Path("docs/verification/stage-7/dashboard-media-archive-regression.txt"),
    "REQ-5.3-04": Path("docs/verification/stage-7/dashboard-broadcast-console-regression.txt"),
    "REQ-5.3-05": Path("docs/verification/stage-7/dashboard-voice-player-regression.txt"),
}
STALE_CLAIMS = {
    "REQ-5.2-04": "本轮未形成真实浏览器播放证据",
    "REQ-5.3-04": "本轮未新增浏览器发送证据",
}
SUMMARY_PATTERN = re.compile(
    r"当前为\s*(\d+)\s*项未实现、\s*(\d+)\s*项软件完成待实机、"
    r"\s*(\d+)\s*项已完成"
)
REQ_PATTERN = re.compile(r"^REQ-(5\.[1-6])-(\d{2})$")


@dataclass(frozen=True)
class RequirementRow:
    line_number: int
    cells: tuple[str, ...]
    raw: str

    @property
    def requirement_id(self) -> str:
        return self.cells[0] if self.cells else ""


def markdown_cells(line: str) -> tuple[str, ...]:
    stripped = line.strip()
    if not stripped.startswith("|") or not stripped.endswith("|"):
        return ()
    return tuple(part.strip() for part in stripped[1:-1].split("|"))


class TraceabilityValidator:
    def __init__(self, document: Path, project_root: Path = PROJECT_ROOT) -> None:
        self.document = document
        self.project_root = project_root
        self.errors: list[str] = []

    def error(self, message: str) -> None:
        self.errors.append(message)

    def validate(self) -> list[str]:
        try:
            text = self.document.read_text(encoding="utf-8")
        except OSError as error:
            return [f"document: cannot read {self.document}: {error}"]
        lines = text.splitlines()
        self._validate_header(lines)
        rows = self._parse_rows(lines)
        self._validate_rows(rows)
        self._validate_summary(text, rows)
        self._validate_evidence(rows)
        return self.errors

    def _validate_header(self, lines: list[str]) -> None:
        headers = [
            (number, markdown_cells(line))
            for number, line in enumerate(lines, 1)
            if markdown_cells(line)[:1] == ("REQ",)
        ]
        if len(headers) != 1:
            self.error(f"header: expected exactly one REQ table header, found {len(headers)}")
            return
        number, cells = headers[0]
        if cells != EXPECTED_COLUMNS:
            self.error(
                f"line {number}: columns must be {' | '.join(EXPECTED_COLUMNS)}"
            )

    def _parse_rows(self, lines: list[str]) -> list[RequirementRow]:
        rows: list[RequirementRow] = []
        for number, line in enumerate(lines, 1):
            if not line.lstrip().startswith("| REQ-"):
                continue
            cells = markdown_cells(line)
            rows.append(RequirementRow(number, cells, line))
        return rows

    def _validate_rows(self, rows: list[RequirementRow]) -> None:
        if not rows:
            self.error("requirements: no REQ rows found")
            return
        seen: dict[str, int] = {}
        actual_order: list[str] = []
        for row in rows:
            if len(row.cells) != len(EXPECTED_COLUMNS):
                self.error(
                    f"line {row.line_number}: expected {len(EXPECTED_COLUMNS)} columns, "
                    f"found {len(row.cells)}"
                )
                continue
            requirement_id = row.requirement_id
            match = REQ_PATTERN.fullmatch(requirement_id)
            if not match:
                self.error(f"line {row.line_number}: invalid requirement ID {requirement_id!r}")
                continue
            if requirement_id in seen:
                self.error(
                    f"line {row.line_number}: duplicate {requirement_id}; "
                    f"first declared on line {seen[requirement_id]}"
                )
            else:
                seen[requirement_id] = row.line_number
            actual_order.append(requirement_id)
            if not row.cells[1].startswith(match.group(1)):
                self.error(
                    f"line {row.line_number}: clause {row.cells[1]!r} does not match {requirement_id}"
                )
            for column, value in zip(EXPECTED_COLUMNS[1:], row.cells[1:]):
                if not value:
                    self.error(f"line {row.line_number}: {column} must not be empty")
            status = row.cells[8]
            blockers = row.cells[9]
            if status not in VALID_STATUSES:
                self.error(f"line {row.line_number}: invalid status {status!r}")
            elif status == "已完成" and blockers not in {"无", "-", "—"}:
                self.error(
                    f"line {row.line_number}: completed requirement must declare no blockers"
                )
            elif status != "已完成" and blockers in {"无", "-", "—"}:
                self.error(
                    f"line {row.line_number}: incomplete requirement must name its blockers"
                )

        missing = [item for item in EXPECTED_REQUIREMENTS if item not in seen]
        unexpected = [item for item in seen if item not in EXPECTED_REQUIREMENTS]
        if missing:
            self.error(f"requirements: missing IDs: {', '.join(missing)}")
        if unexpected:
            self.error(f"requirements: unexpected IDs: {', '.join(unexpected)}")
        if actual_order != list(EXPECTED_REQUIREMENTS):
            self.error("requirements: rows must follow the canonical 5.1 through 5.6 order")

    def _validate_summary(self, text: str, rows: list[RequirementRow]) -> None:
        match = SUMMARY_PATTERN.search(text)
        if not match:
            self.error("summary: current status counts are missing")
            return
        actual = {status: 0 for status in VALID_STATUSES}
        for row in rows:
            if len(row.cells) == len(EXPECTED_COLUMNS) and row.cells[8] in actual:
                actual[row.cells[8]] += 1
        declared = tuple(int(value) for value in match.groups())
        calculated = (
            actual["未实现"],
            actual["软件完成待实机"],
            actual["已完成"],
        )
        if declared != calculated:
            self.error(
                f"summary: declared counts {declared} do not match rows {calculated}"
            )

    def _validate_evidence(self, rows: list[RequirementRow]) -> None:
        by_id = {row.requirement_id: row for row in rows}
        for requirement_id, relative_path in REQUIRED_BROWSER_EVIDENCE.items():
            row = by_id.get(requirement_id)
            if row and relative_path.name not in row.raw:
                self.error(
                    f"{requirement_id}: must link current browser evidence {relative_path.name}"
                )
            evidence_path = self.project_root / relative_path
            try:
                if not evidence_path.is_file() or evidence_path.stat().st_size == 0:
                    self.error(f"{requirement_id}: evidence is missing or empty: {relative_path}")
            except OSError as error:
                self.error(f"{requirement_id}: cannot inspect {relative_path}: {error}")
        for requirement_id, stale_claim in STALE_CLAIMS.items():
            row = by_id.get(requirement_id)
            if row and stale_claim in row.raw:
                self.error(f"{requirement_id}: stale claim remains: {stale_claim}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("document", nargs="?", type=Path, default=DEFAULT_DOCUMENT)
    parser.add_argument("--project-root", type=Path, default=PROJECT_ROOT)
    args = parser.parse_args()
    errors = TraceabilityValidator(args.document, args.project_root).validate()
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(f"PASS: requirements traceability ({len(EXPECTED_REQUIREMENTS)} requirements)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
