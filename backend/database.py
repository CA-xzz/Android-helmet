"""Database compatibility layer for local SQLite and production PostgreSQL."""

from __future__ import annotations

import re
from collections.abc import Callable, Sequence
from typing import Any


class HybridRow(dict[str, Any]):
    """Dictionary row that also supports the few positional reads in the repository."""

    def __init__(self, columns: Sequence[str], values: Sequence[Any]) -> None:
        if len(columns) != len(values):
            raise ValueError("database row column and value counts differ")
        super().__init__(zip(columns, values))
        self._values = tuple(values)

    def __getitem__(self, key: str | int) -> Any:
        if isinstance(key, int):
            return self._values[key]
        return super().__getitem__(key)


def hybrid_row_factory(cursor: Any) -> Callable[[Sequence[Any]], HybridRow]:
    columns = [column.name for column in (cursor.description or ())]

    def make_row(values: Sequence[Any]) -> HybridRow:
        return HybridRow(columns, values)

    return make_row


def _replace_placeholders(sql: str) -> str:
    output: list[str] = []
    quote: str | None = None
    index = 0
    while index < len(sql):
        character = sql[index]
        if quote is not None:
            output.append(character)
            if character == quote:
                if index + 1 < len(sql) and sql[index + 1] == quote:
                    output.append(sql[index + 1])
                    index += 1
                else:
                    quote = None
        elif character in {"'", '"'}:
            quote = character
            output.append(character)
        elif character == "?":
            output.append("%s")
        else:
            output.append(character)
        index += 1
    return "".join(output)


def translate_postgres_sql(sql: str, *, schema: bool = False) -> str:
    stripped = sql.strip()
    table_info = re.fullmatch(r"PRAGMA\s+table_info\(([A-Za-z0-9_]+)\)", stripped, re.I)
    if table_info:
        table_name = table_info.group(1)
        return (
            "SELECT column_name AS name FROM information_schema.columns "
            "WHERE table_schema = current_schema() AND table_name = "
            f"'{table_name}'"
        )

    translated = sql
    if schema:
        translated = re.sub(r"\bINTEGER\b", "BIGINT", translated, flags=re.I)
    translated = re.sub(
        r"json_extract\((metadata_json|payload_json), '\$\.([A-Za-z][A-Za-z0-9]*)'\)",
        r"(\1::jsonb ->> '\2')",
        translated,
        flags=re.I,
    )
    translated = re.sub(r"\bAS\s+INTEGER\b", "AS BIGINT", translated, flags=re.I)
    insert_ignored = bool(re.match(r"\s*INSERT\s+OR\s+IGNORE\s+INTO\b", translated, re.I))
    if insert_ignored:
        translated = re.sub(
            r"^(\s*)INSERT\s+OR\s+IGNORE\s+INTO\b",
            r"\1INSERT INTO",
            translated,
            count=1,
            flags=re.I,
        )
        translated = translated.rstrip().rstrip(";") + " ON CONFLICT DO NOTHING"
    return _replace_placeholders(translated)


class PostgresConnectionAdapter:
    def __init__(self, connection: Any) -> None:
        self.connection = connection

    def __enter__(self) -> PostgresConnectionAdapter:
        self.connection.__enter__()
        return self

    def __exit__(self, exc_type: Any, exc_value: Any, traceback: Any) -> Any:
        return self.connection.__exit__(exc_type, exc_value, traceback)

    def execute(self, sql: str, parameters: Sequence[Any] = ()) -> Any:
        return self.connection.execute(translate_postgres_sql(sql), parameters)

    def executescript(self, script: str) -> None:
        for statement in script.split(";"):
            if statement.strip():
                self.connection.execute(translate_postgres_sql(statement, schema=True))


def connect_postgres(
    database_url: str,
    connect_factory: Callable[..., Any] | None = None,
) -> PostgresConnectionAdapter:
    if connect_factory is None:
        try:
            import psycopg
        except ImportError as error:
            raise RuntimeError("psycopg is required for PostgreSQL production storage") from error
        connect_factory = psycopg.connect
    connection = connect_factory(
        database_url,
        row_factory=hybrid_row_factory,
        connect_timeout=10,
    )
    return PostgresConnectionAdapter(connection)
