"""Live Vertex AI smoke check through the service's own LLM layer:  python -m app.llm.smoke

Makes one real Gemini request per tier and one real embedding request. Prints identifiers and measurements only: never
credentials, tokens or the credential file path.
"""
from __future__ import annotations

import asyncio
import json
import sys

from app.config.settings import get_settings
from app.llm.base import LLMError
from app.llm.vertex import VertexGemini
from app.obs.usage import UsageRecorder


async def main() -> int:
    settings = get_settings()
    llm = VertexGemini(settings, UsageRecorder(settings.price_table))
    failures = 0
    for tier in ("intent", "reasoning"):
        try:
            r = await llm.generate('Return a short JSON object confirming you are reachable: {"reachable": true}',
                                   tier=tier, json_mode=True, max_tokens=100, temperature=0)
            parsed = json.loads(r.text)
            print(f"GEMINI {tier:<9} model={r.model} location={settings.vertex_location} project={llm.project} "
                  f"latency_ms={r.latency_ms} tokens_in={r.tokens_in} tokens_out={r.tokens_out} reachable={parsed.get('reachable')}")
        except (LLMError, json.JSONDecodeError) as e:
            failures += 1
            print(f"GEMINI {tier:<9} FAILED: {getattr(e, 'code', type(e).__name__)}")
    try:
        e = await llm.embed(["Kashmir willow cricket bat for beginners"], task_type="RETRIEVAL_DOCUMENT")
        match = "matches" if e.dimension == settings.embedding_dim else f"DOES NOT MATCH configured AI_EMBEDDING_DIM={settings.embedding_dim}"
        failures += e.dimension != settings.embedding_dim
        print(f"EMBED  model={e.model} dimension={e.dimension} ({match}) latency_ms={e.latency_ms}")
    except LLMError as err:
        failures += 1
        print(f"EMBED  FAILED: {err.code}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
