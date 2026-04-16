# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0

from __future__ import annotations

import logging
import time

import anthropic as anthropic_sdk

from .base import LLMAdapter, LLMResult, SchemaContext
from prompt.builder import PromptBuilder

logger = logging.getLogger(__name__)


class AnthropicAdapter(LLMAdapter):
    """
    Calls the Anthropic Messages API (Claude) to generate SQL.
    Schema context is pre-fetched and injected via PromptBuilder.
    The system prompt instructs Claude to return only SQL with no commentary,
    which keeps extraction trivial.
    """

    def __init__(
        self,
        api_key: str,
        model: str = "claude-sonnet-4-6",
        max_tokens: int = 4096,
    ) -> None:
        self._model = model
        self._max_tokens = max_tokens
        self._client = anthropic_sdk.AsyncAnthropic(api_key=api_key)
        self._prompt_builder = PromptBuilder()

    # ── LLMAdapter interface ─────────────────────────────────────────────────

    async def generate_sql(
        self,
        question: str,
        schema: SchemaContext,
        dialect: str,
        request_id: str = "",
    ) -> LLMResult:
        system_prompt, user_prompt = self._prompt_builder.build(
            question=question,
            schema=schema,
            dialect=dialect,
        )
        t0 = time.monotonic()
        logger.debug(
            "anthropic request",
            extra={"request_id": request_id, "model": self._model},
        )
        message = await self._client.messages.create(
            model=self._model,
            max_tokens=self._max_tokens,
            system=system_prompt,
            messages=[{"role": "user", "content": user_prompt}],
            temperature=0.0,
        )
        elapsed_ms = int((time.monotonic() - t0) * 1000)

        raw = message.content[0].text if message.content else ""
        sql = self._extract_sql(raw)

        usage = message.usage
        return LLMResult(
            sql=sql,
            model=self._model,
            adapter=self.adapter_name,
            prompt_tokens=usage.input_tokens,
            completion_tokens=usage.output_tokens,
            generation_time_ms=elapsed_ms,
            raw_response=raw,
        )

    async def health_check(self) -> dict:
        try:
            # Minimal probe: list models endpoint is lightweight
            models = await self._client.models.list()
            names = [m.id for m in models.data] if hasattr(models, "data") else []
            return {
                "status": "ok",
                "detail": f"Anthropic API reachable. Model: {self._model}",
                "available_models": names[:10],
            }
        except anthropic_sdk.AuthenticationError:
            return {"status": "error", "detail": "Invalid Anthropic API key."}
        except Exception as exc:
            return {"status": "error", "detail": str(exc)}

    @property
    def adapter_name(self) -> str:
        return "anthropic"

    @property
    def model_name(self) -> str:
        return self._model
