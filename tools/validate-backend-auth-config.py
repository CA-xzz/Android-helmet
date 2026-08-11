#!/usr/bin/env python3
"""Validate a production backend authorization configuration."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys


PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from backend.media_service import load_auth_configuration  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("config", type=Path)
    arguments = parser.parse_args()
    configuration = load_auth_configuration(arguments.config)
    if configuration.schema_version != 2:
        raise SystemExit("production auth config must use schemaVersion 2")
    print(
        f"valid schemaVersion=2 organizations={len(set(configuration.device_organizations.values()))} "
        f"devices={len(configuration.device_organizations)} principals={len(configuration.principals)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
