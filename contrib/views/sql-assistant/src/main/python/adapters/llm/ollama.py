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

import httpx

from .base import LLMAdapter, LLMResult, SchemaContext
from prompt.builder import PromptBuilder

logger = logging.getLogger(__name__)


class OllamaAdapter(LLMAdapter):
    """
    Calls a local Ollama server (/api/chat) to generate SQL.
    Schema context is pre-fetched by the caller and injected into the prompt
    via PromptBuilder, so Ollama itself needs no MCP awareness.
    """

    def __init__(
        self,
        base_url: str,
        model: str,
        timeout_seconds: int = 120,
        num_ctx: int = 8192,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._timeout = timeout_seconds
        self._num_ctx = num_ctx
        self._prompt_builder = PromptBuilder()
        self._client = httpx.AsyncClient(
            base_url=self._base_url,
            timeout=httpx.Timeout(timeout_seconds),
        )

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
        payload = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "stream": False,
            "options": {
                "num_ctx": self._num_ctx,
                "temperature": 0.0,
            },
        }
        t0 = time.monotonic()
        logger.debug(
            "ollama request",
            extra={"request_id": request_id, "model": self._model},
        )
        response = await self._client.post("/api/chat", json=payload)
        response.raise_for_status()
        elapsed_ms = int((time.monotonic() - t0) * 1000)

        data = response.json()
        raw = data.get("message", {}).get("content", "")
        sql = self._extract_sql(raw)

        usage = data.get("usage", {})
        return LLMResult(
            sql=sql,
            model=self._model,
            adapter=self.adapter_name,
            prompt_tokens=usage.get("prompt_tokens", 0),
            completion_tokens=usage.get("completion_tokens", 0),
            generation_time_ms=elapsed_ms,
            raw_response=raw,
        )

    async def health_check(self) -> dict:
        try:
            resp = await self._client.get("/api/tags", timeout=5)
            if resp.status_code == 200:
                models = [m["name"] for m in resp.json().get("models", [])]
                model_loaded = self._model in models
                return {
                    "status": "ok",
                    "detail": f"Ollama reachable. Model '{self._model}' "
                              f"{'available' if model_loaded else 'NOT found – run: ollama pull ' + self._model}.",
                    "available_models": models,
                }
            return {"status": "error", "detail": f"HTTP {resp.status_code}"}
        except Exception as exc:
            return {"status": "error", "detail": str(exc)}

    @property
    def adapter_name(self) -> str:
        return "ollama"

    @property
    def model_name(self) -> str:
        return self._model
