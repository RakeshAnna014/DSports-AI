from __future__ import annotations

import asyncio
import hmac
import logging
import uuid

from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi import APIRouter, Depends, Request, Response

from app.agent.schemas import ChatRequest, ChatResponse, ConfirmRequest, RecommendationsResponse
from app.auth.jwt import Principal, current_principal, optional_principal, verify_token
from app.errors import ApiException
from app.reco.engine import RecoRequest
from app.reco.policy import Scenario
from app.obs import metrics
from app.tools.dsports_http import forward_headers

log = logging.getLogger(__name__)
router = APIRouter()


# ------------------------------------------------------------------------------------------------ assistant

@router.post("/v1/chat", tags=["assistant"], response_model=ChatResponse, response_model_by_alias=True)
async def chat(body: ChatRequest, request: Request, principal: Principal | None = Depends(optional_principal)) -> ChatResponse:
    """One assistant turn. Anonymous callers are guests (product discovery and knowledge only). The identity of a signed-in
    customer comes ONLY from the verified token, never from the body."""
    return await request.app.state.chat.chat(principal, body)


@router.post("/v1/confirm", tags=["assistant"], response_model=ChatResponse, response_model_by_alias=True)
async def confirm(body: ConfirmRequest, request: Request, principal: Principal = Depends(current_principal)) -> ChatResponse:
    """Execute a previously proposed action. The token is single-use, expires, and only works for the customer it was issued to."""
    return await request.app.state.chat.confirm(principal, body)


@router.get("/v1/recommendations", tags=["assistant"], response_model=RecommendationsResponse, response_model_by_alias=True)
async def recommendations(request: Request, productIds: str = "", limit: int = 4, referenceProductId: str | None = None,
                          scenario: str | None = None, profileId: str | None = None, budget: float | None = None,
                          principal: Principal | None = Depends(optional_principal)) -> RecommendationsResponse:
    """Explainable recommendations from real DSports data (no model call).

    Legacy mode: `productIds` are the products in the cart or being viewed; with none, a signed-in customer's own order history is used.

    Scenario mode (any of `referenceProductId`, `scenario`, `profileId`, `budget`): `scenario` is COMPLEMENTARY, FREQUENTLY_BOUGHT_TOGETHER,
    SIMILAR, UPGRADE or PERSONALIZED (default: COMPLEMENTARY with a reference product, else PERSONALIZED). A complementary item is kept to a
    configurable share of the reference product's effective price (default 20%) unless an explicit `budget` (INR) is given, which always wins.
    The customer is always the token's subject; `profileId` must be a profile of that account. Every item carries factual reasons."""
    if any(v is not None for v in (referenceProductId, scenario, profileId, budget)):
        try:
            chosen = Scenario(scenario.strip().upper()) if scenario else None
        except ValueError:
            raise ApiException(400, "VALIDATION_ERROR", f"Unknown scenario. Allowed: {[s.value for s in Scenario]}")
        req = RecoRequest(reference_product_id=referenceProductId, scenario=chosen, profile_id=profileId, budget=budget, limit=limit)
        return RecommendationsResponse(**await request.app.state.chat.recommend(principal, [], limit, req))
    ids = [p for p in productIds.split(",") if p.strip()]
    return RecommendationsResponse(**await request.app.state.chat.recommend(principal, ids, limit))


@router.get("/v1/support/conversation", tags=["assistant"])
async def case_conversation(request: Request, caseRef: str, principal: Principal = Depends(current_principal)) -> dict:
    """The customer's latest assistant conversation about one of THEIR cases, so it can be resumed from the case page. The case must be the
    caller's own (DSports decides that with the caller's token); someone else's case is 'not found'."""
    return await request.app.state.chat.case_conversation(principal, caseRef)


@router.get("/v1/offers/recommend", tags=["assistant"])
async def recommend_checkout_offers(request: Request, checkoutId: str, principal: Principal = Depends(current_principal)) -> dict:
    """Personalised, explained ordering of the offers the backend says are valid for the customer's own checkout. Amounts and eligibility
    come from DSports; a reason is only given when it is true of this customer's cart, orders or profile. No model call."""
    try:
        checkout_id = str(uuid.UUID(checkoutId))
    except ValueError:
        raise ApiException(400, "VALIDATION_ERROR", "checkoutId must be a UUID")
    return await request.app.state.chat.recommend_offers(principal, checkout_id)


@router.get("/metrics", tags=["observability"], include_in_schema=False)
async def prometheus(request: Request, credentials: HTTPAuthorizationCredentials | None = Depends(HTTPBearer(auto_error=False))) -> Response:
    """Operational metrics. Open in local development; anywhere else it needs an administrator token or the configured scrape token."""
    settings = request.app.state.settings
    if settings.env.lower() not in ("local", "test", "dev"):
        if credentials is None or credentials.scheme.lower() != "bearer" or not credentials.credentials:
            raise ApiException(401, "UNAUTHORIZED", "Authentication is required")
        scrape = settings.metrics_token.get_secret_value() if settings.metrics_token is not None else None
        if not (scrape and hmac.compare_digest(credentials.credentials, scrape)):
            if not verify_token(credentials.credentials, settings).is_admin:
                raise ApiException(403, "FORBIDDEN", "Metrics are restricted to administrators")
    return Response(metrics.render(), media_type="text/plain; version=0.0.4")


# ------------------------------------------------------------------------------------------------ health

@router.get("/health", tags=["health"])
async def health() -> dict[str, str]:
    """Liveness of this process only. Never depends on the database, DSports or the LLM."""
    return {"status": "UP", "service": "dsports-ai"}


@router.get("/health/dependencies", tags=["health"])
async def dependencies(request: Request, principal: Principal | None = Depends(optional_principal)) -> dict:
    """Dependency view. Always 200: a degraded dependency is reported, not turned into an outage of this endpoint.

    The LLM is deliberately not called here (cost, latency, and `/health` must not depend on it); only whether it is
    configured and the circuit-breaker state are reported. Use `python -m app.llm.smoke` for a live check.
    """
    state = request.app.state
    settings = state.settings
    checks = await asyncio.gather(_database(state), _dsports(state), _tika(state))
    breaker = state.services.llm.breaker.state if getattr(state, "services", None) else "closed"
    llm_ok = _llm_configured(settings) and breaker != "open"
    deps = {
        "database": checks[0],
        "dsports_backend": checks[1],
        "tika": checks[2],
        "llm": {"status": ("CONFIGURED" if llm_ok else "UNAVAILABLE" if breaker == "open" else "NOT_CONFIGURED"), "probed": False,
                "circuit_breaker": breaker},
    }
    ok = ("UP", "CONFIGURED", "DISABLED")
    overall = "UP" if all(d["status"] in ok for d in deps.values()) else "DEGRADED"
    # Models, hosts, locations and limits are operational detail: administrators only. Anyone else (and anonymous probes) gets the statuses.
    out = {"status": overall, "dependencies": deps}
    if principal is not None and principal.is_admin:
        out["config"] = settings.public_view()
    return out


@router.get("/v1/me", tags=["auth"])
async def me(principal: Principal = Depends(current_principal)) -> dict:
    """The identity the service derived from the verified token (hash only: the raw user id is never exposed)."""
    return {"customerHash": principal.customer_hash, "roles": list(principal.roles)}


def _llm_configured(settings) -> bool:
    return bool(settings.google_application_credentials or settings.google_cloud_project)


async def _database(state) -> dict:
    store = state.store
    if store is None:
        return {"status": "DISABLED" if state.settings.database_url is None else "DOWN",
                "detail": state.store_error or "AI_DATABASE_URL is not set"}
    try:
        await store.ping()
        counts = await store.pool.fetchrow("SELECT (SELECT count(*) FROM product_index) AS products, (SELECT count(*) FROM documents) AS documents, "
                                           "(SELECT count(*) FROM chunks) AS chunks")
        return {"status": "UP", "embedding_config": state.embedding_config,
                "index": {"products": counts["products"], "documents": counts["documents"], "chunks": counts["chunks"]}}
    except Exception:
        return {"status": "DOWN", "detail": "ping failed"}


async def _dsports(state) -> dict:
    try:
        response = await state.http.get("/actuator/health", headers=forward_headers())
        return {"status": "UP" if response.status_code == 200 else "DOWN", "httpStatus": response.status_code}
    except Exception as exc:
        return {"status": "DOWN", "detail": type(exc).__name__}


async def _tika(state) -> dict:
    tika = getattr(getattr(state, "services", None), "tika", None)
    if tika is None:
        return {"status": "DISABLED"}
    try:
        return {"status": "UP", "version": await asyncio.wait_for(tika.version(), 3)}
    except Exception:
        return {"status": "DOWN"}
