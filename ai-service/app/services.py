"""Composition root: builds every collaborator once. Used by the web app lifespan and by the CLI (index / ingest / reindex)."""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any

import httpx

from app.cache import TTLCache
from app.config.settings import Settings
from app.guard.confirmation import ConfirmationService
from app.engineering.repository import LogRepository
from app.inventory.repository import InventoryRepository
from app.llm.resilient import CircuitBreaker, ResilientLLM
from app.llm.vertex import VertexGemini
from app.obs.usage import UsageRecorder
from app.rag.embedder import Embedder
from app.rag.ingest import Ingestor
from app.rag.products import ProductIndexer
from app.rag.retrieval import Retriever
from app.rag.tika import TikaClient
from app.router.tier1 import Tier1
from app.shop.catalog import Catalog
from app.shop.search import ProductSearch
from app.store.db import Store
from app.store.state import MemoryStateStore, PgStateStore, ResilientStateStore, StateStore
from app.tools.build import build_registry
from app.tools.actions import ActionExecutor
from app.tools.dsports_client import DSportsClient
from app.tools.registry import ToolRegistry

log = logging.getLogger(__name__)


@dataclass
class Services:
    settings: Settings
    http: httpx.AsyncClient
    dsports: DSportsClient
    catalog: Catalog
    usage: UsageRecorder
    llm: ResilientLLM
    embedder: Embedder
    state: StateStore
    registry: ToolRegistry
    confirmations: ConfirmationService
    executor: ActionExecutor
    tier1: Tier1
    store: Store | None = None
    retriever: Retriever | None = None
    search: ProductSearch | None = None
    tika: TikaClient | None = None
    indexer: ProductIndexer | None = None
    ingestor: Ingestor | None = None
    inventory: InventoryRepository | None = None
    engineering: LogRepository | None = None
    sourcing: Any = None                # SourcingService (Product & Supplier Intelligence); None when there is no AI database
    planning: Any = None                # PlanningService (Sales & Inventory Planning); None when there is no AI database
    knowledge_cache: TTLCache = field(default_factory=lambda: TTLCache(3600, 512))
    _known: tuple[float, set[str]] | None = None

    async def known_products(self) -> set[str]:
        """Lower-cased names of catalogue products, used by the grounding check to recognise product mentions."""
        if self._known and time.monotonic() - self._known[0] < 3600:
            return self._known[1]
        names: set[str] = set()
        if self.store is not None:
            try:
                names = {r["name"].lower() for r in await self.store.pool.fetch("SELECT name FROM product_index")}
            except Exception:
                log.warning("could not load known product names", exc_info=True)
        self._known = (time.monotonic(), names)
        return names

    async def aclose(self) -> None:
        await self.http.aclose()
        if self.tika:
            await self.tika.aclose()
        if self.store:
            await self.store.close()


async def build_services(settings: Settings, *, store: Store | None = None, http: httpx.AsyncClient | None = None,
                         llm_inner: Any | None = None, retriever: Any | None = None, state: StateStore | None = None) -> Services:
    from app.tools.dsports_http import build_client
    http = http or build_client(settings.dsports_base_url, settings.timeout_s)
    dsports = DSportsClient(http)
    catalog = Catalog(dsports)
    usage = UsageRecorder(settings.price_table, sink=store)
    inner = llm_inner or VertexGemini(settings, usage)
    llm = ResilientLLM(inner, CircuitBreaker(settings.breaker_failures, settings.breaker_cooldown_s))
    embedder = Embedder(llm, settings.embedding_model, settings.embedding_dim, settings.cache_ttl_s)

    if state is None:
        memory = MemoryStateStore(settings.conversation_ttl_h)
        state = ResilientStateStore(PgStateStore(store.pool, settings.conversation_ttl_h), memory) if store is not None else memory
    registry = build_registry()
    confirmations = ConfirmationService(settings.customer_hash_key.get_secret_value(), state, settings.confirmation_ttl_s)
    executor = ActionExecutor(registry, confirmations, state)
    svc = Services(settings, http, dsports, catalog, usage, llm, embedder, state, registry, confirmations, executor,
                   Tier1(llm, settings.cache_ttl_s), store=store, knowledge_cache=TTLCache(settings.cache_ttl_s, 512))
    if retriever is not None or store is not None:
        svc.retriever = retriever or Retriever(store, embedder, settings.retrieval_candidates, settings.min_similarity)  # type: ignore[arg-type]
        svc.search = ProductSearch(svc.retriever, catalog)
    if store is not None:
        svc.tika = TikaClient(settings.tika_url, min_chars_per_page=settings.ocr_min_chars_per_page)
        svc.indexer = ProductIndexer(store, catalog, embedder)
        svc.ingestor = Ingestor(store, svc.tika, embedder, settings)
        svc.inventory = InventoryRepository(store.pool)
        svc.engineering = LogRepository(store.pool)
        from app.sourcing.repository import SourcingRepository
        from app.sourcing.service import SourcingService
        svc.sourcing = SourcingService(SourcingRepository(store.pool), settings, catalog)
        from app.auth.jwt import customer_hash
        from app.planning.repository import PlanningRepository
        from app.planning.service import PlanningService
        hash_key = settings.customer_hash_key.get_secret_value()
        svc.planning = PlanningService(PlanningRepository(store.pool), settings, dsports, lambda uid: customer_hash(uid, hash_key))
        svc.planning.llm = llm
    return svc
