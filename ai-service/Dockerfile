# syntax=docker/dockerfile:1
FROM python:3.13-slim AS build
COPY --from=ghcr.io/astral-sh/uv:0.12 /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
WORKDIR /app
# Dependencies first (cached layer), resolved exactly from uv.lock.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY app ./app
COPY corpus ./corpus
RUN uv sync --frozen --no-dev

FROM python:3.13-slim
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PATH="/app/.venv/bin:$PATH"
RUN useradd --system --uid 10001 --no-create-home aiservice
WORKDIR /app
COPY --from=build /app /app
USER aiservice
EXPOSE 8090
# The image carries the SAMPLE knowledge-base corpus so `python -m app.cli ingest-corpus` can run from the container.
# No secrets are baked in: JWT_SECRET, AI_CUSTOMER_HASH_KEY, AI_DATABASE_URL and the Google credential are supplied at
# run time (environment variables / a mounted credential file referenced by GOOGLE_APPLICATION_CREDENTIALS).
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8090/health', timeout=2).status == 200 else 1)"
CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8090", "--no-access-log"]
