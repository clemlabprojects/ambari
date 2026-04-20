# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import annotations

from typing import Literal
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ── Service ─────────────────────────────────────────────────────────────
    host: str = "0.0.0.0"
    port: int = 8095
    log_level: str = "INFO"
    max_result_rows: int = 10_000
    query_timeout_seconds: int = 120

    # ── LLM ─────────────────────────────────────────────────────────────────
    llm_adapter: Literal["ollama", "anthropic", "openai"] = "ollama"

    # Ollama
    ollama_base_url: str = "http://localhost:11434"
    ollama_model: str = "sqlcoder:7b"
    ollama_timeout_seconds: int = 120
    ollama_num_ctx: int = 8192

    # Anthropic / Claude
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-sonnet-4-6"
    anthropic_max_tokens: int = 4096

    # OpenAI (future)
    openai_api_key: str = ""
    openai_model: str = "gpt-4o"
    openai_base_url: str = "https://api.openai.com/v1"

    # ── Catalog ──────────────────────────────────────────────────────────────
    catalog_adapter: Literal["polaris_mcp", "information_schema"] = "polaris_mcp"

    # Polaris MCP
    polaris_mcp_url: str = "http://localhost:8181"
    polaris_mcp_transport: Literal["sse", "http"] = "sse"
    polaris_mcp_token: str = ""
    polaris_mcp_client_id: str = ""
    polaris_mcp_client_secret: str = ""
    polaris_schema_cache_ttl_seconds: int = 300

    # ── SQL Executors ────────────────────────────────────────────────────────
    # Trino
    trino_host: str = "localhost"
    trino_port: int = 8080
    trino_user: str = "trino"
    trino_password: str = ""
    trino_http_scheme: str = "http"
    trino_verify: bool = True

    # Hive
    hive_host: str = "localhost"
    hive_port: int = 10000
    hive_user: str = "hive"
    hive_password: str = ""
    hive_auth: str = "KERBEROS"
    hive_kerberos_service_name: str = "hive"

    # ── History ──────────────────────────────────────────────────────────────
    history_db_path: str = "/var/lib/sql-assistant/history.db"
    history_max_entries: int = 1000


settings = Settings()
