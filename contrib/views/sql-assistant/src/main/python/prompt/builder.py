# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0

from __future__ import annotations

from adapters.llm.base import SchemaContext


_SYSTEM_TRINO = """\
You are an expert SQL analyst specializing in Trino (formerly PrestoSQL).
Your sole task is to translate a natural-language question into a single, \
correct Trino SQL query.

Rules:
- Use only the tables and columns listed in the schema below.
- Return ONLY the SQL query – no explanation, no markdown fences, no comments.
- Use standard Trino SQL syntax (e.g. date_trunc, approx_distinct, UNNEST).
- Qualify all table names as catalog.namespace.table.
- Prefer CTEs over deeply nested subqueries for readability.
- For aggregations always include the appropriate GROUP BY.
- When the question implies a LIMIT, add one (default 100 rows).
- If the question cannot be answered with the given schema, respond with exactly:
  -- UNABLE_TO_ANSWER: <reason>
"""

_SYSTEM_HIVE = """\
You are an expert SQL analyst specializing in Apache Hive (HiveQL).
Your sole task is to translate a natural-language question into a single, \
correct HiveQL query.

Rules:
- Use only the tables and columns listed in the schema below.
- Return ONLY the SQL query – no explanation, no markdown fences, no comments.
- Use HiveQL syntax (e.g. FROM_UNIXTIME, DATE_FORMAT, LATERAL VIEW EXPLODE).
- Qualify table names as schema.table.
- For aggregations always include the appropriate GROUP BY.
- When the question implies a LIMIT, add one (default 100 rows).
- If the question cannot be answered with the given schema, respond with exactly:
  -- UNABLE_TO_ANSWER: <reason>
"""


def _render_schema(schema: SchemaContext) -> str:
    lines: list[str] = []
    for t in schema.tables:
        full_name = f"{schema.catalog}.{schema.namespace}.{t.name}"
        lines.append(f"Table: {full_name}")
        if t.description:
            lines.append(f"  Description: {t.description}")
        for col in t.columns:
            nullable = "" if col.nullable else " NOT NULL"
            desc = f"  -- {col.description}" if col.description else ""
            lines.append(f"  {col.name}  {col.data_type}{nullable}{desc}")
        lines.append("")
    return "\n".join(lines).strip()


class PromptBuilder:
    """
    Builds (system_prompt, user_prompt) tuples for the LLM.
    Dialect-specific system prompts are stored inline; table schema is rendered
    as a structured DDL-like block so models see familiar SQL notation.
    """

    _SYSTEMS: dict[str, str] = {
        "trino": _SYSTEM_TRINO,
        "hive": _SYSTEM_HIVE,
    }

    def build(
        self,
        question: str,
        schema: SchemaContext,
        dialect: str,
    ) -> tuple[str, str]:
        """
        Returns (system_prompt, user_prompt).

        The system prompt sets the role and dialect-specific rules.
        The user prompt contains the schema block and the question so that
        models that do not support system prompts can receive everything in one
        message.
        """
        system = self._SYSTEMS.get(dialect.lower(), _SYSTEM_TRINO)
        schema_block = _render_schema(schema)
        user = (
            f"## Available Schema\n\n{schema_block}\n\n"
            f"## Question\n\n{question}"
        )
        return system, user
