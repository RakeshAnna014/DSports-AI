"""Engineering AI operations:  uv run python -m app.engineering.cli <command>

  ingest  --service backend|ai-service [--path FILE] [--tail N] [--min-level INFO]   parse + redact + store a log file (idempotent)
  docker  --container NAME --service postgres|tika [--since 24h]                      ingest `docker logs` of an infrastructure container
  synthetic [--keep]                                                                  load the labelled SYNTHETIC incident (replaces the previous one)
  deploy  --service S --version V [--ts ISO] [--note TEXT]                            record a deployment (there is no CI/CD feed to read it from)
  ask     "question" [--origin real|synthetic] [--llm]                                run the investigation (engineer-level access is the CLI's local trust)
  status                                                                              what is connected and how much is stored
Default log paths come from ENG_BACKEND_LOG / ENG_AI_LOG, else %TEMP%/dsports_backend.log and %TEMP%/ai_service_main.log.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from app.config.settings import get_settings
from app.engineering import ingest
from app.engineering.investigate import Investigator
from app.engineering.repository import LogRepository
from app.obs.logging import configure_logging
from app.store.db import Store


default_path = ingest.default_path


async def run(args: argparse.Namespace) -> int:
    settings = get_settings()
    configure_logging("WARNING")
    if settings.database_url is None:
        print("AI_DATABASE_URL is not set", file=sys.stderr)
        return 2
    store = await Store.connect(settings.database_url.get_secret_value(), settings.timeout_s, settings.embedding_dim)
    repo = LogRepository(store.pool)
    try:
        if args.command == "ingest":
            path = Path(args.path) if args.path else default_path(args.service)
            print(json.dumps(await ingest.ingest_file(repo, path, args.service, "real", args.min_level, args.tail), indent=2))
        elif args.command == "docker":
            print(json.dumps(await ingest.ingest_docker(repo, args.container, args.service, args.since, args.min_level), indent=2))
        elif args.command == "synthetic":
            print(json.dumps(await ingest.ingest_synthetic(repo, replace=not args.keep), indent=2, default=str))
        elif args.command == "deploy":
            ts = datetime.fromisoformat(args.ts.replace("Z", "+00:00")) if args.ts else datetime.now(timezone.utc)
            print(json.dumps({"id": await repo.add_deployment(ts, args.service, args.version, "real", args.note)}))
        elif args.command == "ask":
            llm = None
            if args.llm:
                from app.services import build_services
                llm = (await build_services(settings, store=store)).llm
            out = await Investigator(repo, llm).run(args.question, origin=args.origin, use_llm=bool(args.llm))
            print(out["summary"]["text"])
            print(f"\n[{out['summary']['source']} summary; {len(out['evidence'])} evidence record(s); {out['meta']['latency_ms']} ms]")
            if args.json:
                print(json.dumps(out, indent=2, default=str))
        elif args.command == "status":
            print(json.dumps({"sources": await repo.coverage(), "deployments": len(await repo.deployments(datetime(2000, 1, 1, tzinfo=timezone.utc), datetime(2100, 1, 1, tzinfo=timezone.utc)))}, indent=2, default=str))
        return 0
    finally:
        await store.close()


def main() -> int:
    p = argparse.ArgumentParser(prog="app.engineering.cli")
    sub = p.add_subparsers(dest="command", required=True)
    a = sub.add_parser("ingest"); a.add_argument("--service", choices=["backend", "ai-service"], required=True); a.add_argument("--path"); a.add_argument("--tail", type=int); a.add_argument("--min-level", default="INFO", choices=["DEBUG", "INFO", "WARN", "ERROR"])
    d = sub.add_parser("docker"); d.add_argument("--container", required=True); d.add_argument("--service", choices=["postgres", "tika"], required=True); d.add_argument("--since", default="24h"); d.add_argument("--min-level", default="INFO", choices=["DEBUG", "INFO", "WARN", "ERROR"])
    s = sub.add_parser("synthetic"); s.add_argument("--keep", action="store_true")
    dp = sub.add_parser("deploy"); dp.add_argument("--service", required=True); dp.add_argument("--version", required=True); dp.add_argument("--ts"); dp.add_argument("--note")
    q = sub.add_parser("ask"); q.add_argument("question"); q.add_argument("--origin", choices=["real", "synthetic"]); q.add_argument("--llm", action="store_true"); q.add_argument("--json", action="store_true")
    sub.add_parser("status")
    return asyncio.run(run(p.parse_args()))


if __name__ == "__main__":
    sys.exit(main())
