from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI
from mcp.server.transport_security import TransportSecuritySettings

from app import __version__
from app.agent.service import ChatService
from app.api.routes import router
from app.config.settings import Settings, get_settings
from app.errors import install_error_handlers
from app.engineering.api import router as engineering_router
from app.inventory.api import router as inventory_router
from app.inventory.copilot import InventoryCopilot
from app.mcp.server import build_mcp_server
from app.obs.logging import configure_logging
from app.obs.middleware import install_request_context
from app.services import build_services
from app.planning.api import router as planning_router
from app.sourcing.api import router as sourcing_router
from app.support.staff_api import router as support_staff_router
from app.store.db import Store

log = logging.getLogger(__name__)


def create_app(settings: Settings | None = None, **overrides: Any) -> FastAPI:
    """`overrides` (llm_inner, retriever, state, store) let tests substitute collaborators; production passes none."""
    settings = settings or get_settings()
    configure_logging(settings.log_level)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        store = overrides.get("store")
        app.state.store_error = None
        app.state.embedding_config = None
        if store is None and settings.database_url is not None:
            try:
                store = await Store.connect(settings.database_url.get_secret_value(), settings.timeout_s, settings.embedding_dim)
            except Exception:
                # The AI service degrades instead of refusing to start; commerce never depends on it.
                store = None
                app.state.store_error = "database unavailable at startup"
                log.error("AI database unavailable at startup", exc_info=True)
        if store is not None:
            app.state.embedding_config = await store.check_embedding_config(settings.embedding_model, settings.embedding_dim)
            if app.state.embedding_config == "reindex_required":
                log.warning("embedding model/dimension changed since vectors were built: run the reindex command")
        services = await build_services(settings, store=store, **{k: v for k, v in overrides.items() if k != "store"})
        app.state.store = store
        app.state.services = services
        app.state.http = services.http
        app.state.usage = services.usage
        app.state.llm = services.llm
        app.state.chat = ChatService(services)
        app.state.inventory_copilot = InventoryCopilot(services)
        app.state.mcp_asgi = None
        mcp_server = None
        if settings.mcp_enabled:
            hosts = [h.strip() for h in settings.mcp_allowed_hosts.split(",") if h.strip()]
            mcp_server = build_mcp_server(services)
            app.state.mcp_asgi = mcp_server.streamable_http_app(
                streamable_http_path="/", transport_security=TransportSecuritySettings(allowed_hosts=hosts, allowed_origins=[]))
        log.info("ai-service started", extra={"config": settings.public_view(), "version": __version__})
        try:
            if mcp_server is not None:
                async with mcp_server.session_manager.run():
                    yield
            else:
                yield
        finally:
            await services.aclose()

    # The interactive docs and the OpenAPI document list every internal route: not served in production.
    docs_on = settings.env.lower() not in ("prod", "production")
    app = FastAPI(title="DSports AI Service", version=__version__, lifespan=lifespan, docs_url="/docs" if docs_on else None, redoc_url="/redoc" if docs_on else None, openapi_url="/openapi.json" if docs_on else None)
    app.state.settings = settings
    app.dependency_overrides[get_settings] = lambda: settings
    install_request_context(app)
    install_error_handlers(app)
    app.include_router(router)
    app.include_router(inventory_router)
    app.include_router(engineering_router)
    app.include_router(support_staff_router)
    app.include_router(sourcing_router)
    app.include_router(planning_router)

    async def mcp_mount(scope, receive, send):
        """The MCP app is built in the lifespan (it needs the services), so the mount delegates to it lazily."""
        target = app.state.mcp_asgi
        if target is None:
            from starlette.responses import JSONResponse
            await JSONResponse({"code": "MCP_DISABLED", "message": "MCP is not enabled"}, status_code=404)(scope, receive, send)
            return
        await target(scope, receive, send)

    app.mount("/mcp", mcp_mount)
    return app
