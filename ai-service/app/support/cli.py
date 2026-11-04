"""Support knowledge operations:  uv run python -m app.support.cli ingest-corpus [--dir corpus_support] [--force]

Ingests the INTERNAL support documents (document_type sup_*). The retriever never returns them to customers: that separation is enforced in SQL
(Retriever.search_chunks), not here. The customer-facing support procedures are part of the main corpus (`python -m app.cli ingest-corpus`).
"""
from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

from app.config.settings import get_settings
from app.obs.logging import configure_logging
from app.services import build_services
from app.store.db import Store


async def run(args: argparse.Namespace) -> int:
    settings = get_settings()
    configure_logging(settings.log_level)
    if settings.database_url is None:
        print("AI_DATABASE_URL is not set", file=sys.stderr)
        return 2
    store = await Store.connect(settings.database_url.get_secret_value(), settings.timeout_s, settings.embedding_dim)
    services = await build_services(settings, store=store)
    try:
        results = await services.ingestor.ingest_manifest(Path(args.dir), {}, force=args.force)
        for r in results:
            print(f"{r.status:<9} {r.source:<46} pages={r.pages} chunks={r.chunks} embedded={r.embedded} reused={r.reused}")
        return 0
    finally:
        await services.aclose()


def main() -> None:
    ap = argparse.ArgumentParser(prog="python -m app.support.cli")
    sub = ap.add_subparsers(dest="command", required=True)
    ing = sub.add_parser("ingest-corpus")
    ing.add_argument("--dir", default="corpus_support")
    ing.add_argument("--force", action="store_true")
    sys.exit(asyncio.run(run(ap.parse_args())))


if __name__ == "__main__":
    main()
