"""Inventory Intelligence operations:  uv run python -m app.inventory.cli <command>

  seed [--as-of YYYY-MM-DD]   rebuild the mart: live catalogue + POC reference + POC SYNTHETIC history (replaces all inv_* data)
  train                       data quality, backtests, model selection, forecasts, event impact, elasticity, assessments -> persisted
  status                      what the mart and the current model run contain
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
from datetime import date
from pathlib import Path

from app.config.settings import get_settings
from app.inventory import pipeline
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
        if args.command == "seed":
            print(json.dumps(await pipeline.seed(store.pool, services.catalog, date.fromisoformat(args.as_of) if args.as_of else None), indent=2))
        elif args.command == "train":
            out = await pipeline.train(store.pool)
            print(json.dumps(out, indent=2, default=str))
        elif args.command == "ingest-corpus":
            results = await services.ingestor.ingest_manifest(Path(args.dir or "corpus_inventory"), {}, force=args.force)
            for r in results:
                print(f"{r.status:<9} {r.source:<46} pages={r.pages} chunks={r.chunks} embedded={r.embedded} reused={r.reused}")
        elif args.command == "status":
            row = await store.pool.fetchrow(
                "SELECT (SELECT count(*) FROM inv_sku) skus, (SELECT count(*) FROM inv_demand_daily) demand_rows, (SELECT count(*) FROM inv_event) events, "
                "(SELECT count(*) FROM inv_model_run) runs, (SELECT model_name FROM inv_model_run WHERE is_current) current_model, "
                "(SELECT as_of FROM inv_model_run WHERE is_current) as_of")
            print(json.dumps(dict(row), indent=2, default=str))
        return 0
    finally:
        await services.aclose()


def main() -> None:
    ap = argparse.ArgumentParser(prog="python -m app.inventory.cli")
    sub = ap.add_subparsers(dest="command", required=True)
    seed = sub.add_parser("seed")
    seed.add_argument("--as-of")
    sub.add_parser("train")
    ing = sub.add_parser("ingest-corpus")
    ing.add_argument("--dir")
    ing.add_argument("--force", action="store_true")
    sub.add_parser("status")
    sys.exit(asyncio.run(run(ap.parse_args())))


if __name__ == "__main__":
    main()
