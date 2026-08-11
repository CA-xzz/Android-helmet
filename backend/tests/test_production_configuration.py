from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from backend.media_service import (
    AuthConfiguration,
    load_runtime_secrets,
    parse_ice_urls,
    validate_production_configuration,
)


class ProductionConfigurationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.configuration = AuthConfiguration({}, {}, 2)
        self.values = {
            "auth_configuration": self.configuration,
            "token": "",
            "database_url": "postgresql://postgres/helmet",
            "object_store_name": "s3",
            "s3_endpoint": "https://s3.example.test",
            "s3_bucket": "helmet-media",
            "s3_region": "region-1",
            "s3_access_key_id": "production-access-key",
            "s3_secret_access_key": "production-secret-key",
            "stun_urls": ("stun:stun.example.test:3478",),
            "turn_urls": ("turn:turn.example.test:3478?transport=udp",),
            "turn_shared_secret": "secret-from-deployment-system",
            "map_tile_url_template": "https://tiles.example.test/{z}/{x}/{y}.png",
            "map_attribution": "Approved map provider",
            "mqtt_enabled": True,
        }

    def validate(self, **changes: object) -> None:
        values = {**self.values, **changes}
        validate_production_configuration(**values)

    def test_complete_configuration_is_accepted(self) -> None:
        self.validate()

    def test_documented_direct_script_entrypoint_loads(self) -> None:
        project_root = Path(__file__).resolve().parents[2]
        result = subprocess.run(
            [sys.executable, str(project_root / "backend" / "media_service.py"), "--help"],
            cwd=project_root,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_production_dependencies_are_required_and_fail_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "STUN URL"):
            self.validate(stun_urls=())
        with self.assertRaisesRegex(ValueError, "S3 object storage"):
            self.validate(object_store_name="local")
        with self.assertRaisesRegex(ValueError, "S3 endpoint must use HTTPS"):
            self.validate(s3_endpoint="http://s3.example.test")
        with self.assertRaisesRegex(ValueError, "S3 access key ID"):
            self.validate(s3_access_key_id="")
        with self.assertRaisesRegex(ValueError, "map tile URL template must use HTTPS"):
            self.validate(map_tile_url_template="http://127.0.0.1/{z}/{x}/{y}.png")
        with self.assertRaisesRegex(ValueError, "single-token"):
            self.validate(token="development-token")
        with self.assertRaisesRegex(ValueError, "MQTT mutual-TLS gateway"):
            self.validate(mqtt_enabled=False)

    def test_ice_urls_validate_scheme_endpoint_port_and_query(self) -> None:
        self.assertEqual(
            ("turn:turn.example.test:3478?transport=udp", "turns:turn.example.test:5349?transport=tcp"),
            parse_ice_urls(
                "turn:turn.example.test:3478?transport=udp, turns:turn.example.test:5349?transport=tcp",
                {"turn", "turns"},
                "TURN URLs",
            ),
        )
        for invalid in (
            "https://turn.example.test",
            "turn:user@turn.example.test:3478",
            "turn:turn.example.test:70000",
            "turn:turn.example.test:3478?transport=sctp",
            "turn:turn.example.test:3478#fragment",
            "turn:[broken-ipv6:3478",
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                parse_ice_urls(invalid, {"turn", "turns"}, "TURN URLs")

    def test_production_runtime_secrets_are_loaded_from_physical_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            password = root / "postgres-password"
            access_key = root / "s3-access-key"
            secret_key = root / "s3-secret-key"
            turn_secret = root / "turn-secret"
            values = {
                password: "database password/with delimiter\n",
                access_key: "production-access-key\n",
                secret_key: "production-secret-key\n",
                turn_secret: "production-turn-secret-at-least-32-bytes\n",
            }
            for path, value in values.items():
                path.write_text(value, encoding="utf-8")
                path.chmod(0o600)
            secrets = load_runtime_secrets(
                {
                    "HELMET_POSTGRES_PASSWORD_FILE": str(password),
                    "HELMET_POSTGRES_USER": "helmet user",
                    "HELMET_POSTGRES_HOST": "postgres",
                    "HELMET_POSTGRES_PORT": "5432",
                    "HELMET_POSTGRES_DB": "helmet/database",
                    "HELMET_DATABASE_SSLMODE": "disable",
                    "HELMET_S3_ACCESS_KEY_FILE": str(access_key),
                    "HELMET_S3_SECRET_KEY_FILE": str(secret_key),
                    "HELMET_TURN_SECRET_FILE": str(turn_secret),
                },
                production=True,
            )
            self.assertEqual(
                "postgresql://helmet%20user:database%20password%2Fwith%20delimiter"
                "@postgres:5432/helmet%2Fdatabase?sslmode=disable",
                secrets.database_url,
            )
            self.assertEqual("production-access-key", secrets.s3_access_key_id)
            self.assertEqual("production-secret-key", secrets.s3_secret_access_key)
            self.assertEqual(
                "production-turn-secret-at-least-32-bytes",
                secrets.turn_shared_secret,
            )

    def test_production_runtime_rejects_direct_secret_environment_values(self) -> None:
        for name in (
            "HELMET_DATABASE_URL",
            "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY",
            "HELMET_TURN_SHARED_SECRET",
        ):
            with self.subTest(name=name), self.assertRaisesRegex(
                ValueError, "production mode does not permit"
            ):
                load_runtime_secrets({name: "must-not-enter-process-environment"}, True)

    def test_production_runtime_rejects_symlinked_database_password(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            password = root / "password"
            password.write_text("database-password", encoding="utf-8")
            password.chmod(0o600)
            link = root / "password-link"
            link.symlink_to(password)
            with self.assertRaisesRegex(ValueError, "physical regular file"):
                load_runtime_secrets(
                    {"HELMET_POSTGRES_PASSWORD_FILE": str(link)},
                    production=True,
                )


if __name__ == "__main__":
    unittest.main()
