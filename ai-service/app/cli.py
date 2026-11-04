"""Operations CLI:  uv run python -m app.cli <command>

  status                      what is indexed, and whether the embedding config needs a reindex
  index-products [--force]    (re)build the derived product index from the DSports catalogue
  ingest-corpus [--dir D] [--force]   Tika -> OCR if scanned -> chunk -> embed -> pgvector, for every document in the manifest
  reindex [--force]           re-embed everything after AI_EMBEDDING_MODEL / AI_EMBEDDING_DIM changed
  ingest-file PATH --source S --type T [--title X]   ingest one extra document (used by the evaluation's poisoned-document case)
  remove-document SOURCE      delete one ingested document and its chunks
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
from dataclasses import asdict
from pathlib import Path

from app.config.settings import get_settings
from app.obs.logging import configure_logging
from app.rag.reindex import reindex
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
        if args.command == "status":
            row = await store.pool.fetchrow("SELECT (SELECT count(*) FROM product_index) AS products, (SELECT count(*) FROM documents) AS documents, "
                                            "(SELECT count(*) FROM chunks) AS chunks, (SELECT count(*) FROM documents WHERE ocr_used) AS ocr_documents")
            config = await store.check_embedding_config(settings.embedding_model, settings.embedding_dim)
            print(json.dumps({**dict(row), "embedding_model": settings.embedding_model, "embedding_dim": settings.embedding_dim,
                              "embedding_config": config}, indent=2))
        elif args.command == "index-products":
            print(json.dumps(asdict(await services.indexer.index_all(force=args.force)), indent=2))
        elif args.command == "ingest-corpus":
            directory = Path(args.dir or settings.corpus_dir)
            await services.indexer.index_all()                   # product ids are needed to link product-specific documents
            results = await services.ingestor.ingest_manifest(directory, await services.indexer.sku_to_id(), force=args.force)
            for r in results:
                print(f"{r.status:<9} {r.source:<42} pages={r.pages} chunks={r.chunks} embedded={r.embedded} reused={r.reused} ocr={r.ocr_used}")
            await store.check_embedding_config(settings.embedding_model, settings.embedding_dim)
        elif args.command == "ingest-file":
            from app.rag.ingest import DocSpec
            path = Path(args.path)
            r = await services.ingestor.ingest(DocSpec(file=path.name, source=args.source, document_type=args.type, title=args.title), path.read_bytes(), force=True)
            print(f"{r.status} {r.source} pages={r.pages} chunks={r.chunks}")
        elif args.command == "reindex":
            print(json.dumps(asdict(await reindex(store, services.indexer, services.embedder, force=args.force)), indent=2))
        elif args.command == "remove-document":
            print("removed" if await services.ingestor.remove(args.source) else "not found")
        return 0
    finally:
        await services.aclose()


def main() -> None:
    parser = argparse.ArgumentParser(prog="python -m app.cli")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("status")
    p = sub.add_parser("index-products"); p.add_argument("--force", action="store_true")
    p = sub.add_parser("ingest-corpus"); p.add_argument("--dir"); p.add_argument("--force", action="store_true")
    p = sub.add_parser("reindex"); p.add_argument("--force", action="store_true")
    p = sub.add_parser("ingest-file"); p.add_argument("path"); p.add_argument("--source", required=True); p.add_argument("--type", required=True); p.add_argument("--title")
    p = sub.add_parser("remove-document"); p.add_argument("source")
    sys.exit(asyncio.run(run(parser.parse_args())))


if __name__ == "__main__":
    main()
