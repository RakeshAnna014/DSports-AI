"""Environment-driven configuration. Nothing environment-specific or secret has a default value in code."""
from __future__ import annotations

import json
import os
from functools import lru_cache

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore", populate_by_name=True)

    # --- runtime
    env: str = Field("local", validation_alias="AI_ENV")
    log_level: str = Field("INFO", validation_alias="AI_LOG_LEVEL")

    # --- authentication: the same HS256 secret the DSports backend signs with (read-only use)
    jwt_secret: SecretStr = Field(validation_alias="JWT_SECRET")
    customer_hash_key: SecretStr = Field(validation_alias="AI_CUSTOMER_HASH_KEY")
    # Access tokens carry an issuer and an audience (the Java backend sets both); a token minted for anything else is refused even if the signature is valid.
    # Prometheus scraping outside local development: an administrator token, or this static scrape token (compared in constant time).
    metrics_token: SecretStr | None = Field(None, validation_alias="AI_METRICS_TOKEN")
    jwt_issuer: str = Field("dsports", validation_alias="JWT_ISSUER", min_length=1, max_length=80)
    jwt_audience: str = Field("dsports-api", validation_alias="JWT_AUDIENCE", min_length=1, max_length=80)

    # --- DSports backend (source of truth)
    dsports_base_url: str = Field("http://localhost:8080", validation_alias="DSPORTS_BASE_URL")

    # --- AI database (derived AI state only). Unset => the store is disabled and reported as such.
    database_url: SecretStr | None = Field(None, validation_alias="AI_DATABASE_URL")

    # --- Google / Vertex AI. Credentials are read by google-auth from GOOGLE_APPLICATION_CREDENTIALS.
    google_application_credentials: str | None = Field(None, validation_alias="GOOGLE_APPLICATION_CREDENTIALS")
    google_cloud_project: str | None = Field(None, validation_alias="GOOGLE_CLOUD_PROJECT")
    vertex_location: str = Field("us-central1", validation_alias="AI_VERTEX_LOCATION")
    # Embeddings may live in a closer region than the Gemini models (measured: ~0.3 s vs ~1.7 s per query from India).
    embedding_location: str | None = Field(None, validation_alias="AI_EMBEDDING_LOCATION")

    # --- model tiers (T1 cheap structured output, T2 reasoning) and embeddings
    intent_model: str = Field("gemini-2.5-flash-lite", validation_alias="AI_INTENT_MODEL")
    reasoning_model: str = Field("gemini-2.5-flash", validation_alias="AI_REASONING_MODEL")
    embedding_model: str = Field("text-embedding-005", validation_alias="AI_EMBEDDING_MODEL")
    # Stored with the model name; a change of either means existing vectors must be re-indexed.
    embedding_dim: int = Field(768, validation_alias="AI_EMBEDDING_DIM", ge=1, le=4096)

    # --- limits
    # Gemini 2.5 "thinking" tokens count against the output limit and are billed as output. Our prompts are short, fully specified
    # explanation tasks, so thinking is off by default (0). Set -1 for dynamic thinking or a positive token budget.
    thinking_budget: int = Field(0, validation_alias="AI_THINKING_BUDGET", ge=-1, le=24576)
    max_tokens: int = Field(1024, validation_alias="AI_MAX_TOKENS", ge=1, le=65536)
    temperature: float = Field(0.2, validation_alias="AI_TEMPERATURE", ge=0.0, le=2.0)
    top_k: int = Field(5, validation_alias="AI_TOP_K", ge=1, le=50)
    max_tool_calls: int = Field(8, validation_alias="AI_MAX_TOOL_CALLS", ge=0, le=50)
    timeout_s: float = Field(30.0, validation_alias="AI_TIMEOUT_S", gt=0, le=300)

    # --- agent / request limits
    request_timeout_s: float = Field(90.0, validation_alias="AI_REQUEST_TIMEOUT_S", gt=0, le=600)
    request_token_budget: int = Field(8000, validation_alias="AI_REQUEST_TOKEN_BUDGET", ge=100)
    max_iterations: int = Field(12, validation_alias="AI_MAX_ITERATIONS", ge=1, le=100)
    conversation_ttl_h: float = Field(24.0, validation_alias="AI_CONVERSATION_TTL_H", gt=0)
    # Support investigations: a shipment with no tracking event for this many days is reported as stalled. A DEMO threshold, not an official DSports SLA.
    support_stall_days: int = Field(3, validation_alias="AI_SUPPORT_STALL_DAYS", ge=1, le=30)
    confirmation_ttl_s: int = Field(300, validation_alias="AI_CONFIRMATION_TTL_S", ge=30, le=3600)
    cache_ttl_s: int = Field(3600, validation_alias="AI_CACHE_TTL_S", ge=0)
    rate_limit_per_min: int = Field(30, validation_alias="AI_RATE_LIMIT_PER_MIN", ge=1)

    # --- budget- and context-aware recommendations (deterministic; the LLM never sets a price band)
    # A complementary item (a grip for a bat) is shown up to this share of the reference product's effective price: 0.20 -> Rs 10,000 gives Rs 2,000.
    reco_complementary_ceiling_pct: float = Field(0.20, validation_alias="AI_RECO_COMPLEMENTARY_CEILING_PCT", gt=0.0, le=1.0)
    # Progressive, clearly labelled widenings tried only when the base ceiling leaves too few real candidates (comma separated shares).
    reco_complementary_fallback_pcts: str = Field("0.30,0.40", validation_alias="AI_RECO_COMPLEMENTARY_FALLBACK_PCTS")
    reco_similar_tolerance_pct: float = Field(0.25, validation_alias="AI_RECO_SIMILAR_TOLERANCE_PCT", ge=0.0, le=1.0)
    # "Upgrade" shows products priced between these multiples of the reference price (only when the customer asks for premium).
    reco_upgrade_min_multiple: float = Field(1.15, validation_alias="AI_RECO_UPGRADE_MIN_MULTIPLE", gt=1.0)
    reco_upgrade_max_multiple: float = Field(2.0, validation_alias="AI_RECO_UPGRADE_MAX_MULTIPLE", gt=1.0)
    # Fewer real candidates than this triggers the next fallback band.
    reco_min_candidates: int = Field(2, validation_alias="AI_RECO_MIN_CANDIDATES", ge=1, le=10)
    # A pair counts as "frequently bought together" only with at least this many distinct orders as evidence.
    reco_copurchase_min_orders: int = Field(2, validation_alias="AI_RECO_COPURCHASE_MIN_ORDERS", ge=1)
    # At most this many results of one category / brand in a result list (diversity).
    reco_max_per_group: int = Field(2, validation_alias="AI_RECO_MAX_PER_GROUP", ge=1, le=10)

    # --- Product & Supplier Intelligence (Phase 5). Nothing here is a default business fact: no exchange rate, supplier, price or source is built in.
    # SOURCING_FEEDS: JSON list of permitted external sources, e.g. [{"name":"brand-x","url":"https://catalog.brand-x.com/products.json","format":"json","sport":"Badminton",
    #   "supplier":{"name":"Brand X India","type":"MANUFACTURER","website":"https://brand-x.com","country":"IN"}}]. Empty = no external source (research then reports it as unavailable).
    sourcing_feeds: str | None = Field(None, validation_alias="SOURCING_FEEDS")
    sourcing_allowed_hosts: str = Field("", validation_alias="SOURCING_ALLOWED_HOSTS")        # comma separated; a feed host must be listed here (SSRF allow-list)
    # Typed research providers, e.g. [{"type":"victor_sport","max_products":12,"supplier_countries":["India"]}]. A provider only runs if its host is in SOURCING_ALLOWED_HOSTS.
    sourcing_providers: str | None = Field(None, validation_alias="SOURCING_PROVIDERS")
    sourcing_image_hosts: str = Field("", validation_alias="SOURCING_IMAGE_HOSTS")            # hosts a product image may be validated on (their CDN); comma separated
    sourcing_min_interval_s: float = Field(1.0, validation_alias="SOURCING_MIN_INTERVAL_S", ge=0, le=60)    # politeness: minimum gap between requests to one host
    sourcing_brand_aliases: str | None = Field(None, validation_alias="SOURCING_BRAND_ALIASES")    # JSON {"variant": "canonical"}
    sourcing_timeout_s: float = Field(10.0, validation_alias="SOURCING_TIMEOUT_S", gt=0, le=60)
    sourcing_max_bytes: int = Field(1_000_000, validation_alias="SOURCING_MAX_BYTES", ge=1000, le=10_000_000)
    sourcing_base_currency: str = Field("INR", validation_alias="SOURCING_BASE_CURRENCY")
    # {"USD": {"rate": 83.1, "source": "treasury rate sheet 2026-10-14", "as_of": "2026-10-14"}}: operator supplied; a rate with no source/as_of is refused. Empty = no conversion possible.
    sourcing_fx_rates: str | None = Field(None, validation_alias="SOURCING_FX_RATES")
    sourcing_weights: str | None = Field(None, validation_alias="SOURCING_SCORE_WEIGHTS")     # JSON overrides of the default dimension weights
    sourcing_working_capital_limit: float | None = Field(None, validation_alias="SOURCING_WORKING_CAPITAL_LIMIT", gt=0)
    sourcing_lookback_days: int = Field(30, validation_alias="SOURCING_LOOKBACK_DAYS", ge=1, le=365)
    sourcing_demand_min_customers: int = Field(2, validation_alias="SOURCING_DEMAND_MIN_CUSTOMERS", ge=1)
    sourcing_quote_stale_days: int = Field(90, validation_alias="SOURCING_QUOTE_STALE_DAYS", ge=1)       # a quote with no validity date older than this is treated as stale
    sourcing_runs_per_hour: int = Field(6, validation_alias="SOURCING_RUNS_PER_HOUR", ge=1)              # cost control for external calls
    sourcing_enable_demo: bool = Field(True, validation_alias="SOURCING_ENABLE_DEMO")                    # allows the labelled DEMO seed endpoint

    # --- Sales & Inventory Planning (Phase 6). Business calendar and thresholds; none of these is a business fact, they are settings.
    planning_timezone: str = Field("Asia/Kolkata", validation_alias="PLANNING_TIMEZONE")                    # the week a sale belongs to is decided in this zone
    planning_week_start: int = Field(0, validation_alias="PLANNING_WEEK_START", ge=0, le=6)                 # 0 = Monday
    planning_fy_start_month: int = Field(4, validation_alias="PLANNING_FY_START_MONTH", ge=1, le=12)        # financial year start (4 = April)
    planning_horizon_weeks: int = Field(13, validation_alias="PLANNING_HORIZON_WEEKS", ge=1, le=52)
    planning_lookback_weeks: int = Field(104, validation_alias="PLANNING_LOOKBACK_WEEKS", ge=8, le=260)
    planning_min_history_weeks: int = Field(26, validation_alias="PLANNING_MIN_HISTORY_WEEKS", ge=4)        # below this a series uses a comparable/category fallback and is LOW confidence
    planning_backtest_origins: int = Field(6, validation_alias="PLANNING_BACKTEST_ORIGINS", ge=1, le=26)
    planning_backtest_step_weeks: int = Field(4, validation_alias="PLANNING_BACKTEST_STEP_WEEKS", ge=1, le=13)
    planning_min_eval_observations: int = Field(30, validation_alias="PLANNING_MIN_EVAL_OBSERVATIONS", ge=1)    # an accuracy figure on fewer series-weeks is not shown as a number
    planning_min_eval_units: int = Field(100, validation_alias="PLANNING_MIN_EVAL_UNITS", ge=1)
    planning_state_region_map: str | None = Field(None, validation_alias="PLANNING_STATE_REGION_MAP")       # JSON {"KA": "S", ...}: delivery state to region code (replaces the built-in default)
    planning_variance_pct: float = Field(20.0, validation_alias="PLANNING_VARIANCE_PCT", gt=0)              # a week is flagged when actual differs from the forecast/target by more than this
    planning_variance_min_units: float = Field(10.0, validation_alias="PLANNING_VARIANCE_MIN_UNITS", ge=0)  # ... and by at least this many units (small numbers are noise)
    planning_transfer_lead_days: int = Field(3, validation_alias="PLANNING_TRANSFER_LEAD_DAYS", ge=0)      # same-region store-to-store transfer time
    planning_transfer_cross_region_days: int = Field(6, validation_alias="PLANNING_TRANSFER_CROSS_REGION_DAYS", ge=0)
    planning_transfer_min_qty: int = Field(2, validation_alias="PLANNING_TRANSFER_MIN_QTY", ge=1)
    planning_transfer_unit_cost: float | None = Field(None, validation_alias="PLANNING_TRANSFER_UNIT_COST", ge=0)    # unset: transfer cost is NOT claimed
    planning_analyses_per_min: int = Field(6, validation_alias="PLANNING_ANALYSES_PER_MIN", ge=1, le=120)             # each analysis reads a lot of data and may call the model: bounded per person
    planning_allow_self_approval: bool = Field(False, validation_alias="PLANNING_ALLOW_SELF_APPROVAL")
    planning_enable_demo: bool = Field(True, validation_alias="PLANNING_ENABLE_DEMO")                       # allows the labelled demonstration store network and history

    # --- RAG
    tika_url: str = Field("http://localhost:9998", validation_alias="AI_TIKA_URL")
    ocr_min_chars_per_page: int = Field(50, validation_alias="AI_OCR_MIN_CHARS_PER_PAGE", ge=0)
    chunk_tokens: int = Field(450, validation_alias="AI_CHUNK_TOKENS", ge=50, le=2000)
    chunk_overlap_tokens: int = Field(60, validation_alias="AI_CHUNK_OVERLAP_TOKENS", ge=0, le=500)
    min_similarity: float = Field(0.5, validation_alias="AI_MIN_SIMILARITY", ge=0.0, le=1.0)
    retrieval_candidates: int = Field(20, validation_alias="AI_RETRIEVAL_CANDIDATES", ge=5, le=200)
    corpus_dir: str = Field("corpus", validation_alias="AI_CORPUS_DIR")

    # --- MCP server facade (mounted at /mcp). Hosts allowed in the Host header (DNS-rebinding protection); comma separated.
    mcp_enabled: bool = Field(True, validation_alias="AI_MCP_ENABLED")
    mcp_allowed_hosts: str = Field("localhost:*,127.0.0.1:*", validation_alias="AI_MCP_ALLOWED_HOSTS")

    # --- LLM resilience
    breaker_failures: int = Field(3, validation_alias="AI_BREAKER_FAILURES", ge=1)
    breaker_cooldown_s: float = Field(30.0, validation_alias="AI_BREAKER_COOLDOWN_S", gt=0)

    # --- cost estimation: {"model-name": {"input_per_1m": 0.1, "output_per_1m": 0.4}} in USD; empty => unpriced
    price_table_json: str = Field("{}", validation_alias="AI_PRICE_TABLE_JSON")

    @field_validator("jwt_secret")
    @classmethod
    def _jwt_secret_strong(cls, v: SecretStr) -> SecretStr:
        # Same rule as the Java JwtTokenProvider: at least 256 bits of raw UTF-8 bytes.
        if len(v.get_secret_value().encode("utf-8")) < 32:
            raise ValueError("JWT_SECRET must be at least 32 bytes (256 bits)")
        return v

    @field_validator("customer_hash_key")
    @classmethod
    def _hash_key_strong(cls, v: SecretStr) -> SecretStr:
        if len(v.get_secret_value().encode("utf-8")) < 16:
            raise ValueError("AI_CUSTOMER_HASH_KEY must be at least 16 bytes")
        return v

    @model_validator(mode="after")
    def _chunk_overlap_smaller_than_chunk(self) -> "Settings":
        if self.chunk_overlap_tokens >= self.chunk_tokens:
            raise ValueError("AI_CHUNK_OVERLAP_TOKENS must be smaller than AI_CHUNK_TOKENS")
        return self

    @field_validator("dsports_base_url", "tika_url")
    @classmethod
    def _base_url(cls, v: str) -> str:
        if not v.startswith(("http://", "https://")):
            raise ValueError("URL settings must start with http:// or https://")
        return v.rstrip("/")

    @field_validator("log_level")
    @classmethod
    def _level(cls, v: str) -> str:
        v = v.upper()
        if v not in {"DEBUG", "INFO", "WARNING", "ERROR"}:
            raise ValueError("AI_LOG_LEVEL must be DEBUG, INFO, WARNING or ERROR")
        return v

    @field_validator("price_table_json")
    @classmethod
    def _price_table_valid(cls, v: str) -> str:
        try:
            table = json.loads(v)
        except json.JSONDecodeError as e:
            raise ValueError(f"AI_PRICE_TABLE_JSON is not valid JSON: {e.msg}") from e
        if not isinstance(table, dict):
            raise ValueError("AI_PRICE_TABLE_JSON must be a JSON object")
        for model, price in table.items():
            if not isinstance(price, dict) or not {"input_per_1m", "output_per_1m"} <= price.keys():
                raise ValueError(f"AI_PRICE_TABLE_JSON[{model!r}] needs input_per_1m and output_per_1m")
            if any(not isinstance(price[k], (int, float)) or price[k] < 0 for k in ("input_per_1m", "output_per_1m")):
                raise ValueError(f"AI_PRICE_TABLE_JSON[{model!r}] prices must be non-negative numbers")
        return v

    @model_validator(mode="after")
    def _credentials_file_exists(self) -> "Settings":
        path = self.google_application_credentials
        if path and not os.path.isfile(path):
            raise ValueError("GOOGLE_APPLICATION_CREDENTIALS points to a file that does not exist")
        return self

    @property
    def reco_fallback_pcts(self) -> list[float]:
        """The widening shares, ascending and strictly above the base ceiling; anything unparsable is ignored."""
        out: list[float] = []
        for raw in self.reco_complementary_fallback_pcts.split(","):
            try:
                v = float(raw)
            except ValueError:
                continue
            if self.reco_complementary_ceiling_pct < v <= 1.0:
                out.append(v)
        return sorted(set(out))

    @property
    def price_table(self) -> dict[str, dict[str, float]]:
        return json.loads(self.price_table_json)

    def public_view(self) -> dict[str, object]:
        """Configuration safe to log or return: no secrets, no credential path, no connection string."""
        return {
            "env": self.env,
            "dsports_base_url": self.dsports_base_url,
            "database_configured": self.database_url is not None,
            "vertex_location": self.vertex_location,
            "embedding_location": self.embedding_location or self.vertex_location,
            "min_similarity": self.min_similarity,
            "mcp_enabled": self.mcp_enabled,
            "intent_model": self.intent_model,
            "reasoning_model": self.reasoning_model,
            "embedding_model": self.embedding_model,
            "embedding_dim": self.embedding_dim,
            "max_tokens": self.max_tokens,
            "thinking_budget": self.thinking_budget,
            "temperature": self.temperature,
            "top_k": self.top_k,
            "max_tool_calls": self.max_tool_calls,
            "timeout_s": self.timeout_s,
            "reco_complementary_ceiling_pct": self.reco_complementary_ceiling_pct,
            "reco_complementary_fallback_pcts": self.reco_fallback_pcts,
            "request_timeout_s": self.request_timeout_s,
            "request_budget_tokens": self.request_token_budget,
            "tika_url": self.tika_url,
            "chunk_tokens": self.chunk_tokens,
            "chunk_overlap_tokens": self.chunk_overlap_tokens,
            "priced_models": sorted(self.price_table),
        }


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]  # required values come from the environment
