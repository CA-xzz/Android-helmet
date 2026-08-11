from __future__ import annotations

import unittest

from backend.database import (
    HybridRow,
    PostgresConnectionAdapter,
    connect_postgres,
    translate_postgres_sql,
)


class FakeCursor:
    def __init__(self) -> None:
        self.description = [type("Column", (), {"name": "count"})()]


class FakeConnection:
    def __init__(self) -> None:
        self.calls: list[tuple[str, tuple[object, ...]]] = []
        self.entered = False
        self.exited = False

    def __enter__(self) -> FakeConnection:
        self.entered = True
        return self

    def __exit__(self, *args: object) -> None:
        self.exited = True

    def execute(self, sql: str, parameters: tuple[object, ...] = ()) -> str:
        self.calls.append((sql, parameters))
        return "cursor"


class DatabaseCompatibilityTest(unittest.TestCase):
    def test_hybrid_row_supports_named_and_positional_reads(self) -> None:
        row = HybridRow(("count", "name"), (3, "helmet"))
        self.assertEqual(3, row["count"])
        self.assertEqual("helmet", row[1])

    def test_row_factory_tolerates_commands_without_a_result_description(self) -> None:
        from backend.database import hybrid_row_factory

        cursor = type("CommandCursor", (), {"description": None})()
        self.assertEqual({}, hybrid_row_factory(cursor)(()))

    def test_runtime_sql_translation_preserves_quoted_question_marks(self) -> None:
        translated = translate_postgres_sql(
            "SELECT json_extract(metadata_json, '$.deviceId') FROM media_archives "
            "WHERE media_id = ? AND metadata_json != '?' "
            "AND CAST(json_extract(payload_json, '$.statusSequence') AS INTEGER) > ?"
        )
        self.assertIn("metadata_json::jsonb ->> 'deviceId'", translated)
        self.assertIn("payload_json::jsonb ->> 'statusSequence'", translated)
        self.assertIn("AS BIGINT", translated)
        self.assertIn("media_id = %s", translated)
        self.assertIn("metadata_json != '?'", translated)

    def test_insert_ignore_and_schema_translation_are_postgres_compatible(self) -> None:
        self.assertEqual(
            "INSERT INTO media_objects VALUES (%s, %s) ON CONFLICT DO NOTHING",
            translate_postgres_sql("INSERT OR IGNORE INTO media_objects VALUES (?, ?);"),
        )
        schema = translate_postgres_sql(
            "CREATE TABLE sample (sequence INTEGER NOT NULL)", schema=True
        )
        self.assertEqual("CREATE TABLE sample (sequence BIGINT NOT NULL)", schema)

    def test_table_info_uses_current_postgres_schema(self) -> None:
        translated = translate_postgres_sql("PRAGMA table_info(security_audit_events)")
        self.assertIn("information_schema.columns", translated)
        self.assertIn("current_schema()", translated)
        self.assertIn("'security_audit_events'", translated)

    def test_adapter_translates_execute_and_script_inside_transaction(self) -> None:
        connection = FakeConnection()
        adapter = PostgresConnectionAdapter(connection)
        with adapter as database:
            self.assertEqual("cursor", database.execute("SELECT ?", (1,)))
            database.executescript(
                "CREATE TABLE first_table (value INTEGER);"
                "CREATE TABLE second_table (value INTEGER);"
            )
        self.assertTrue(connection.entered)
        self.assertTrue(connection.exited)
        self.assertEqual(("SELECT %s", (1,)), connection.calls[0])
        self.assertIn("BIGINT", connection.calls[1][0])
        self.assertEqual(3, len(connection.calls))

    def test_connect_factory_receives_row_factory_and_timeout(self) -> None:
        captured: dict[str, object] = {}
        connection = FakeConnection()

        def connect_factory(database_url: str, **kwargs: object) -> FakeConnection:
            captured["database_url"] = database_url
            captured.update(kwargs)
            return connection

        adapter = connect_postgres("postgresql://database/helmet", connect_factory)
        self.assertIs(connection, adapter.connection)
        self.assertEqual("postgresql://database/helmet", captured["database_url"])
        self.assertEqual(10, captured["connect_timeout"])
        self.assertTrue(callable(captured["row_factory"]))


if __name__ == "__main__":
    unittest.main()
