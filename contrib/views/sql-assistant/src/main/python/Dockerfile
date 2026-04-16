FROM python:3.11-slim

LABEL org.opencontainers.image.title="SQL Assistant Semantic Service"
LABEL org.opencontainers.image.description="NL-to-SQL service for ODP SQL Assistant Ambari View"
LABEL org.opencontainers.image.vendor="ClemLab"

# System dependencies required by PyHive / SASL
RUN apt-get update && apt-get install -y --no-install-recommends \
    libsasl2-dev \
    libsasl2-modules \
    libsasl2-modules-gssapi-mit \
    krb5-user \
    gcc \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Non-root user for security
RUN useradd -r -u 1001 sqlassistant && \
    mkdir -p /var/lib/sql-assistant && \
    chown sqlassistant:sqlassistant /var/lib/sql-assistant

USER sqlassistant

ENV PYTHONUNBUFFERED=1
ENV HISTORY_DB_PATH=/var/lib/sql-assistant/history.db

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD python -c "import httpx; r = httpx.get('http://localhost:8090/health', timeout=8); exit(0 if r.status_code == 200 else 1)"

CMD ["python", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8090"]
