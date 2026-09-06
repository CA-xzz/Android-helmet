#!/bin/sh

set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PROJECT_ROOT"
python3 -m unittest discover -s backend/tests -v
python3 -m unittest discover -s tools/tests -v
