from __future__ import annotations

from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

from backend.private_file import (
    materialize_owner_only_file,
    materialize_postgres_password_file,
    read_owner_only_file,
    read_owner_only_text,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
BACKEND_ENTRYPOINT = PROJECT_ROOT / "deploy/backend-stack/backend-entrypoint.sh"


class PrivateFileTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.write_text("deployment-secret\r\n", encoding="utf-8")
        self.source.chmod(0o600)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_reads_owner_only_physical_file(self) -> None:
        self.assertEqual(b"deployment-secret\r\n", read_owner_only_file(self.source, 64))
        self.assertEqual("deployment-secret", read_owner_only_text(self.source, 64))

    def test_rejects_symlink_and_group_permissions(self) -> None:
        link = self.root / "source-link"
        link.symlink_to(self.source)
        with self.assertRaisesRegex(ValueError, "physical regular file"):
            read_owner_only_file(link, 64)
        self.source.chmod(0o640)
        with self.assertRaisesRegex(ValueError, "owner-only"):
            read_owner_only_file(self.source, 64)

    def test_rejects_oversized_empty_and_non_text_values(self) -> None:
        with self.assertRaisesRegex(ValueError, "exceeds 4 bytes"):
            read_owner_only_file(self.source, 4)
        self.source.write_bytes(b"\r\n")
        with self.assertRaisesRegex(ValueError, "must not be empty"):
            read_owner_only_text(self.source, 64)
        self.source.write_bytes(b"secret\x00value")
        with self.assertRaisesRegex(ValueError, "NUL"):
            read_owner_only_text(self.source, 64)
        for invalid in ("secret\nsecond", "secret\n\n", "secret\r", " \t \n"):
            with self.subTest(invalid=repr(invalid)):
                self.source.write_text(invalid, encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "single line|must not be empty"):
                    read_owner_only_text(self.source, 64)
        self.source.write_text(" secret with spaces \n", encoding="utf-8")
        self.assertEqual(" secret with spaces ", read_owner_only_text(self.source, 64))

    def test_materializes_atomic_owner_only_copy(self) -> None:
        destination = self.root / "runtime" / "auth-config.json"
        destination.parent.mkdir(mode=0o700)
        materialize_owner_only_file(self.source, destination, 64)
        self.assertEqual(self.source.read_bytes(), destination.read_bytes())
        self.assertEqual(0o600, stat.S_IMODE(destination.stat().st_mode))
        self.assertEqual([], list(destination.parent.glob(".auth-config.json.tmp.*")))

    def test_materialization_rejects_unsafe_parent_and_destination(self) -> None:
        unsafe_parent = self.root / "unsafe"
        unsafe_parent.mkdir(mode=0o755)
        with self.assertRaisesRegex(ValueError, "parent must be owner-only"):
            materialize_owner_only_file(self.source, unsafe_parent / "copy", 64)
        safe_parent = self.root / "safe"
        safe_parent.mkdir(mode=0o700)
        destination = safe_parent / "copy"
        destination.symlink_to(self.source)
        with self.assertRaisesRegex(ValueError, "physical regular file"):
            materialize_owner_only_file(self.source, destination, 64)

    def test_materializes_escaped_postgres_password_file(self) -> None:
        self.source.write_text("password:with\\separator\r\n", encoding="utf-8")
        destination = self.root / "runtime" / "pgpass"
        destination.parent.mkdir(mode=0o700)
        materialize_postgres_password_file(
            self.source,
            destination,
            "helmet:operator\\role",
            64,
        )
        self.assertEqual(
            "*:*:*:helmet\\:operator\\\\role:password\\:with\\\\separator\n",
            destination.read_text(encoding="utf-8"),
        )
        self.assertEqual(0o600, stat.S_IMODE(destination.stat().st_mode))
        with self.assertRaisesRegex(ValueError, "single-line"):
            materialize_postgres_password_file(
                self.source,
                destination,
                "invalid\nuser",
                64,
            )

    def test_command_line_failure_is_closed_without_secret_output(self) -> None:
        link = self.root / "source-link"
        link.symlink_to(self.source)
        result = subprocess.run(
            [sys.executable, "-m", "backend.private_file", "read-text", str(link)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(78, result.returncode)
        self.assertNotIn("deployment-secret", result.stdout + result.stderr)

    def test_backend_entrypoint_rejects_auth_configuration_symlink(self) -> None:
        auth_link = self.root / "auth-link"
        auth_link.symlink_to(self.source)
        result = subprocess.run(
            [str(BACKEND_ENTRYPOINT)],
            cwd=PROJECT_ROOT,
            env={
                "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
                "PYTHONPATH": str(PROJECT_ROOT),
                "HELMET_DATA_DIR": str(self.root / "data"),
                "HELMET_AUTH_SECRET_FILE": str(auth_link),
            },
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(78, result.returncode)
        self.assertIn("invalid deployment secret", result.stderr)
        self.assertFalse((self.root / "data/runtime/auth-config.json").exists())

    def test_backend_service_rejects_password_symlink_after_safe_copy(self) -> None:
        auth = self.root / "auth.json"
        auth.write_text(
            (PROJECT_ROOT / "backend/auth-config.example.json").read_text(
                encoding="utf-8"
            ),
            encoding="utf-8",
        )
        auth.chmod(0o600)
        password_link = self.root / "password-link"
        password_link.symlink_to(self.source)
        result = subprocess.run(
            [str(BACKEND_ENTRYPOINT)],
            cwd=PROJECT_ROOT,
            env={
                "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
                "PYTHONPATH": str(PROJECT_ROOT),
                "HELMET_DATA_DIR": str(self.root / "data"),
                "HELMET_AUTH_SECRET_FILE": str(auth),
                "HELMET_POSTGRES_PASSWORD_FILE": str(password_link),
            },
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(1, result.returncode)
        self.assertIn("physical regular file", result.stderr)
        self.assertNotIn("deployment-secret", result.stdout + result.stderr)
        copied = self.root / "data/runtime/auth-config.json"
        self.assertEqual(auth.read_bytes(), copied.read_bytes())
        self.assertEqual(0o600, stat.S_IMODE(copied.stat().st_mode))


if __name__ == "__main__":
    unittest.main()
