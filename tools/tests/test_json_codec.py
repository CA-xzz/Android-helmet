from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import unittest

from backend.json_codec import StrictJsonError, load_strict_json_file, loads_strict


class StrictJsonCodecTest(unittest.TestCase):
    def test_accepts_standard_utf8_json(self) -> None:
        self.assertEqual(
            {"deviceId": "设备-1", "values": [1, 2.5, None, True]},
            loads_strict('{"deviceId":"设备-1","values":[1,2.5,null,true]}'),
        )

    def test_rejects_duplicate_fields_and_non_standard_numbers(self) -> None:
        for raw in (
            b'{"deviceId":"device-a","deviceId":"device-b"}',
            b'{"value":NaN}',
            b'{"value":Infinity}',
            b'{"value":-Infinity}',
            b'{"value":1e999}',
        ):
            with self.subTest(raw=raw), self.assertRaises(StrictJsonError):
                loads_strict(raw)

    def test_rejects_excessive_nesting_invalid_utf8_and_oversized_files(self) -> None:
        nested: object = 1
        for _ in range(65):
            nested = [nested]
        with self.assertRaisesRegex(StrictJsonError, "nesting"):
            loads_strict(json.dumps(nested))
        with self.assertRaisesRegex(StrictJsonError, "UTF-8"):
            loads_strict(b'\xff')

        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "manifest.json"
            path.write_bytes(b'{"value":1}')
            self.assertEqual({"value": 1}, load_strict_json_file(path, 64))
            with self.assertRaisesRegex(StrictJsonError, "size"):
                load_strict_json_file(path, 4)
            linked = Path(temporary) / "linked.json"
            linked.symlink_to(path)
            with self.assertRaisesRegex(StrictJsonError, "physical regular file"):
                load_strict_json_file(linked, 64)
            fifo = Path(temporary) / "manifest.fifo"
            os.mkfifo(fifo)
            with self.assertRaisesRegex(StrictJsonError, "physical regular file"):
                load_strict_json_file(fifo, 64)


if __name__ == "__main__":
    unittest.main()
