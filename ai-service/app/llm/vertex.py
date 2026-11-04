"""Gemini and embeddings on Vertex AI via the google-genai SDK, authenticated with Application Default Credentials."""
from __future__ import annotations

import logging
import time
from typing import Literal

import google.auth
from google import genai
from google.genai import errors as genai_errors
from google.genai import types

from app.config.settings import Settings
from app.llm.base import EmbeddingResult, LLMError, LLMResult, Tier
from app.obs.usage import UsageRecorder

log = logging.getLogger(__name__)
_SCOPES = ["https://www.googleapis.com/auth/cloud-platform"]


class VertexGemini:
    """Implements both LLMClient and EmbeddingClient. Every call is timed, bounded and recorded in the usage log."""

    def __init__(self, settings: Settings, usage: UsageRecorder):
        self._s = settings
        self._usage = usage
        self._clients: dict[str, genai.Client] = {}
        self.project: str | None = settings.google_cloud_project

    def _get_client(self, location: str | None = None) -> genai.Client:
        location = location or self._s.vertex_location
        if location not in self._clients:
            try:
                credentials, adc_project = google.auth.default(scopes=_SCOPES)
            except google.auth.exceptions.DefaultCredentialsError:
                raise LLMError("LLM_NOT_CONFIGURED", "No Google credentials are configured") from None
            self.project = self._s.google_cloud_project or adc_project
            if not self.project:
                raise LLMError("LLM_NOT_CONFIGURED", "No Google Cloud project could be determined")
            self._clients[location] = genai.Client(
                vertexai=True, project=self.project, location=location, credentials=credentials,
                http_options=types.HttpOptions(timeout=int(self._s.timeout_s * 1000)),
            )
        return self._clients[location]

    def _model_for(self, tier: Tier) -> str:
        return self._s.intent_model if tier == "intent" else self._s.reasoning_model

    async def generate(self, prompt: str, *, tier: Tier = "reasoning", system: str | None = None,
                       json_mode: bool = False, max_tokens: int | None = None, temperature: float | None = None,
                       intent: str | None = None, customer_hash: str | None = None) -> LLMResult:
        model = self._model_for(tier)
        config = types.GenerateContentConfig(
            system_instruction=system,
            max_output_tokens=max_tokens or self._s.max_tokens,
            temperature=self._s.temperature if temperature is None else temperature,
            response_mime_type="application/json" if json_mode else None,
            # Only the 2.5 flash models allow thinking to be switched off (2.5-pro cannot); others ignore the setting.
            thinking_config=types.ThinkingConfig(thinking_budget=self._s.thinking_budget) if model.startswith("gemini-2.5-flash") else None,
        )
        started = time.perf_counter()
        try:
            response = await self._get_client().aio.models.generate_content(model=model, contents=prompt, config=config)
        except LLMError:
            raise
        except Exception as exc:
            code = _classify(exc)
            await self._usage.record(operation="generate", model=model, tokens_in=None, tokens_out=None,
                                     latency_ms=_ms(started), outcome=code, tier=tier, intent=intent,
                                     customer_hash=customer_hash)
            raise LLMError(code, "The model request failed") from exc
        usage = response.usage_metadata
        result = LLMResult(
            text=response.text or "", model=model,
            tokens_in=getattr(usage, "prompt_token_count", None),
            # thinking tokens (if any) are billed as output, so they count as output here
            tokens_out=(getattr(usage, "candidates_token_count", None) or 0) + (getattr(usage, "thoughts_token_count", None) or 0) or None,
            latency_ms=_ms(started),
        )
        await self._usage.record(operation="generate", model=model, tokens_in=result.tokens_in, tokens_out=result.tokens_out,
                                 latency_ms=result.latency_ms, tier=tier, intent=intent, customer_hash=customer_hash)
        return result

    async def embed(self, texts: list[str], *, task_type: Literal["RETRIEVAL_DOCUMENT", "RETRIEVAL_QUERY"],
                    customer_hash: str | None = None) -> EmbeddingResult:
        model = self._s.embedding_model
        started = time.perf_counter()
        try:
            response = await self._get_client(self._s.embedding_location).aio.models.embed_content(
                model=model, contents=texts, config=types.EmbedContentConfig(task_type=task_type))
        except LLMError:
            raise
        except Exception as exc:
            code = _classify(exc)
            await self._usage.record(operation="embed", model=model, tokens_in=None, tokens_out=None,
                                     latency_ms=_ms(started), outcome=code, customer_hash=customer_hash)
            raise LLMError(code, "The embedding request failed") from exc
        vectors = [list(e.values) for e in (response.embeddings or [])]
        if len(vectors) != len(texts) or not vectors:
            raise LLMError("LLM_BAD_RESPONSE", "The embedding response did not match the request")
        dimension = len(vectors[0])
        latency = _ms(started)
        await self._usage.record(operation="embed", model=model, tokens_in=None, tokens_out=None, latency_ms=latency,
                                 customer_hash=customer_hash)
        return EmbeddingResult(vectors=vectors, model=model, dimension=dimension, latency_ms=latency)


def _ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def _classify(exc: Exception) -> str:
    if isinstance(exc, genai_errors.APIError):
        status = getattr(exc, "code", None)
        return {401: "LLM_AUTH", 403: "LLM_PERMISSION_DENIED", 404: "LLM_MODEL_NOT_FOUND", 429: "LLM_QUOTA"}.get(
            status, "LLM_TIMEOUT" if status == 504 else "LLM_ERROR")
    if "timeout" in type(exc).__name__.lower():
        return "LLM_TIMEOUT"
    return "LLM_ERROR"
