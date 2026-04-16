# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass
class LLMResult:
    sql: str
    model: str
    adapter: str
    prompt_tokens: int = 0
    completion_tokens: int = 0
    generation_time_ms: int = 0
    raw_response: str = ""


@dataclass
class SchemaContext:
    """Structured schema context passed to the LLM prompt builder."""
    catalog: str
    namespace: str
    tables: list[TableContext] = field(default_factory=list)


@dataclass
class TableContext:
    name: str
    columns: list[ColumnContext] = field(default_factory=list)
    description: str = ""


@dataclass
class ColumnContext:
    name: str
    data_type: str
    nullable: bool = True
    description: str = ""


class LLMAdapter(ABC):
    """
    Abstract base for all LLM adapters. Every adapter must implement
    generate_sql() and health_check(). The adapter is responsible for prompt
    formatting, calling the underlying model, and extracting clean SQL from
    whatever the model returns.
    """

    @abstractmethod
    async def generate_sql(
        self,
        question: str,
        schema: SchemaContext,
        dialect: str,
        request_id: str = "",
    ) -> LLMResult:
        """
        Translate a natural-language question into a SQL query for the given
        dialect, using the provided schema context.
        """

    @abstractmethod
    async def health_check(self) -> dict:
        """
        Return a status dict: {"status": "ok"|"error", "detail": str}.
        Must not raise – catch all exceptions internally.
        """

    @property
    @abstractmethod
    def adapter_name(self) -> str:
        """Short identifier, e.g. 'ollama', 'anthropic'."""

    @property
    @abstractmethod
    def model_name(self) -> str:
        """Model identifier as reported to the caller."""

    @staticmethod
    def _extract_sql(raw: str) -> str:
        """
        Strip markdown code fences and leading/trailing whitespace from an LLM
        response so only the SQL remains. Handles ```sql...```, ```...```, and
        bare responses.
        """
        text = raw.strip()
        # Remove ```sql ... ``` or ``` ... ```
        if "```" in text:
            lines = text.split("\n")
            inside = False
            result_lines = []
            for line in lines:
                stripped = line.strip()
                if stripped.startswith("```"):
                    inside = not inside
                    continue
                if inside:
                    result_lines.append(line)
            text = "\n".join(result_lines).strip()
        # Remove any leading "SQL:" or "Query:" label
        for prefix in ("SQL:", "Query:", "sql:", "query:"):
            if text.startswith(prefix):
                text = text[len(prefix):].strip()
        return text
