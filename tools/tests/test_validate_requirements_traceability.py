from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = PROJECT_ROOT / "tools" / "validate-requirements-traceability.py"
SOURCE_DOCUMENT = PROJECT_ROOT / "docs" / "REQUIREMENTS_TRACEABILITY.md"
SPEC = importlib.util.spec_from_file_location("requirements_traceability", SCRIPT_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class RequirementsTraceabilityValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.document = Path(self.temporary.name) / "REQUIREMENTS_TRACEABILITY.md"
        self.original = SOURCE_DOCUMENT.read_text(encoding="utf-8")
        self.document.write_text(self.original, encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def validate(self) -> list[str]:
        return MODULE.TraceabilityValidator(self.document, PROJECT_ROOT).validate()

    def write(self, content: str) -> None:
        self.document.write_text(content, encoding="utf-8")

    def test_accepts_current_complete_matrix(self) -> None:
        self.assertEqual([], self.validate())

    def test_rejects_duplicate_missing_and_out_of_order_requirements(self) -> None:
        self.write(self.original.replace("REQ-5.6-04", "REQ-5.6-03", 1))
        errors = self.validate()
        self.assertTrue(any("duplicate REQ-5.6-03" in error for error in errors))
        self.assertTrue(any("missing IDs: REQ-5.6-04" in error for error in errors))
        self.assertTrue(any("canonical" in error for error in errors))

    def test_rejects_summary_mismatch_and_invalid_status(self) -> None:
        modified = self.original.replace(
            "0 项未实现、31 项软件完成待实机、0 项已完成",
            "0 项未实现、30 项软件完成待实机、1 项已完成",
            1,
        ).replace("| 软件完成待实机 | OQ-003 |", "| 待验收 | OQ-003 |", 1)
        self.write(modified)
        errors = self.validate()
        self.assertTrue(any("invalid status" in error for error in errors))
        self.assertTrue(any("declared counts" in error for error in errors))

    def test_rejects_completed_requirement_with_blockers(self) -> None:
        self.write(
            self.original.replace(
                "| 软件完成待实机 | OQ-003 |",
                "| 已完成 | OQ-003 |",
                1,
            )
        )
        errors = self.validate()
        self.assertTrue(any("must declare no blockers" in error for error in errors))

    def test_requires_current_browser_evidence_links(self) -> None:
        self.write(
            self.original.replace(
                "dashboard-media-archive-regression.txt",
                "media evidence omitted",
                1,
            )
        )
        errors = self.validate()
        self.assertTrue(any("REQ-5.2-04" in error and "browser evidence" in error for error in errors))

    def test_rejects_stale_browser_evidence_claims(self) -> None:
        self.write(
            self.original.replace(
                "H618 合成 JPEG 经真实 Worker 上传",
                "本轮未形成真实浏览器播放证据",
                1,
            )
        )
        errors = self.validate()
        self.assertTrue(any("stale claim" in error for error in errors))

    def test_rejects_malformed_table_columns(self) -> None:
        self.write(self.original.replace("| OQ-003 |", "| OQ-003 | unexpected |", 1))
        errors = self.validate()
        self.assertTrue(any("expected 10 columns" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
