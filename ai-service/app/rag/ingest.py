"""Document ingestion: file -> Tika (+OCR when scanned) -> clean -> chunk -> embed -> pgvector.

Idempotent at two levels: an unchanged document (same bytes, metadata, embedding model and chunking parameters) is skipped
entirely, and when a document does change, chunks whose embedding input is unchanged keep their stored embedding, so only
new text is embedded again.
"""
from __future__ import annotations

import json
import logging
import re
import unicodedata
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from app.config.settings import Settings
from app.rag.chunking import Chunk, chunk_text
from app.rag.embedder import Embedder, content_hash, to_pgvector
from app.rag.tika import TikaClient
from app.store.db import Store

log = logging.getLogger(__name__)
_NS = uuid.UUID("5d0a3e0e-8a39-4c6b-9c3f-3f0d4c2b9a11")
_CONTENT_TYPES = {".md": "text/markdown", ".txt": "text/plain", ".pdf": "application/pdf", ".html": "text/html",
                  ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                  ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg"}
_HYPHEN_BREAK = re.compile(r"(\w)-\n(?=[a-z])")
_CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


@dataclass
class DocSpec:
    file: str
    source: str
    document_type: str
    title: str | None = None
    version: str = "1"
    language: str = "en"
    sport: str | None = None
    category: str | None = None
    brand: str | None = None
    product_sku: str | None = None

    @classmethod
    def from_manifest(cls, entry: dict[str, Any]) -> "DocSpec":
        return cls(file=entry["file"], source=entry.get("source", entry["file"]), document_type=entry["documentType"],
                   title=entry.get("title"), version=str(entry.get("version", "1")), language=entry.get("language", "en"),
                   sport=entry.get("sport"), category=entry.get("category"), brand=entry.get("brand"),
                   product_sku=entry.get("productSku"))


@dataclass
class IngestResult:
    source: str
    status: str                       # ingested | unchanged
    pages: int = 0
    chunks: int = 0
    embedded: int = 0
    reused: int = 0
    ocr_used: bool = False
    detail: dict[str, Any] = field(default_factory=dict)


def clean_text(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).replace("\r\n", "\n")
    text = _CONTROL.sub("", text)
    text = _HYPHEN_BREAK.sub(r"\1", text)
    text = re.sub(r"[ \t]+\n", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def embedding_input(title: str | None, chunk: Chunk) -> str:
    """What is embedded: the chunk with its document title and section, so a chunk like "Step 2" still carries its topic."""
    head = " - ".join(p for p in (title, chunk.section) if p)
    return f"{head}\n{chunk.text}" if head else chunk.text


class Ingestor:
    def __init__(self, store: Store, tika: TikaClient, embedder: Embedder, settings: Settings):
        self._store, self._tika, self._embedder, self._s = store, tika, embedder, settings

    async def ingest(self, spec: DocSpec, data: bytes, *, product_id: str | None = None, force: bool = False) -> IngestResult:
        content_type = _CONTENT_TYPES.get(Path(spec.file).suffix.lower())
        meta = {**spec.__dict__, "product_id": product_id}
        doc_hash = content_hash(data.hex(), json.dumps(meta, sort_keys=True), self._embedder.model, str(self._embedder.dim),
                                str(self._s.chunk_tokens), str(self._s.chunk_overlap_tokens))
        pool = self._store.pool
        existing = await pool.fetchrow("SELECT id, content_hash FROM documents WHERE source=$1", spec.source)
        if existing and existing["content_hash"] == doc_hash and not force:
            return IngestResult(spec.source, "unchanged")

        extraction = await self._tika.extract(data, content_type)
        pages = [clean_text(p) for p in extraction.pages]
        chunks: list[tuple[int, Chunk]] = []
        for number, page in enumerate(pages, start=1):
            for c in chunk_text(page, self._s.chunk_tokens, self._s.chunk_overlap_tokens):
                chunks.append((number, Chunk(len(chunks), c.text, c.section, c.tokens)))
        if not chunks:
            raise ValueError(f"{spec.source}: no text could be extracted")

        doc_id = existing["id"] if existing else uuid.uuid5(_NS, spec.source)
        inputs = [embedding_input(spec.title, c) for _, c in chunks]
        hashes = [content_hash(i, self._embedder.model) for i in inputs]
        reusable: dict[str, str] = {}
        if existing:
            rows = await pool.fetch("SELECT content_hash, embedding::text AS e FROM chunks WHERE document_id=$1 AND embedding IS NOT NULL", doc_id)
            reusable = {r["content_hash"]: r["e"] for r in rows}
        missing = [i for i, h in enumerate(hashes) if h not in reusable]
        fresh = await self._embedder.embed_documents([inputs[i] for i in missing]) if missing else []
        vectors: dict[int, str] = {i: to_pgvector(v) for i, v in zip(missing, fresh)}

        async with pool.acquire() as conn, conn.transaction():
            await conn.execute("DELETE FROM chunks WHERE document_id=$1", doc_id)
            await conn.execute(
                """INSERT INTO documents (id, source, document_type, title, version, language, pages, ocr_used, content_hash,
                                          embedding_model, ingested_at)
                   VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10, now())
                   ON CONFLICT (source) DO UPDATE SET document_type=EXCLUDED.document_type, title=EXCLUDED.title,
                     version=EXCLUDED.version, language=EXCLUDED.language, pages=EXCLUDED.pages, ocr_used=EXCLUDED.ocr_used,
                     content_hash=EXCLUDED.content_hash, embedding_model=EXCLUDED.embedding_model, ingested_at=now()""",
                doc_id, spec.source, spec.document_type, spec.title, spec.version, spec.language, len(pages),
                extraction.ocr_used, doc_hash, self._embedder.model)
            for i, (page, chunk) in enumerate(chunks):
                vec = vectors.get(i) or reusable[hashes[i]]
                await conn.execute(
                    """INSERT INTO chunks (id, document_id, product_id, category, sport, brand, document_type, source, version,
                                           language, page, chunk_index, section, content, content_hash, embedding)
                       VALUES ($1,$2,$3::uuid,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16::vector)""",
                    uuid.uuid5(_NS, f"{spec.source}:{chunk.index}:{hashes[i]}"), doc_id, product_id, spec.category, spec.sport,
                    spec.brand, spec.document_type, spec.source, spec.version, spec.language, page, chunk.index,
                    chunk.section, chunk.text, hashes[i], vec)
        log.info("document ingested", extra={"source": spec.source, "pages": len(pages), "chunks": len(chunks),
                                              "embedded": len(missing), "reused": len(chunks) - len(missing),
                                              "ocr_used": extraction.ocr_used})
        return IngestResult(spec.source, "ingested", pages=len(pages), chunks=len(chunks), embedded=len(missing),
                            reused=len(chunks) - len(missing), ocr_used=extraction.ocr_used,
                            detail={"scan": extraction.is_scan, "digital_chars_per_page": extraction.digital_chars_per_page,
                                    "ocr_pages": extraction.ocr_pages})

    async def ingest_manifest(self, directory: Path, product_ids_by_sku: dict[str, str], *, force: bool = False) -> list[IngestResult]:
        manifest = yaml.safe_load((directory / "manifest.yaml").read_text(encoding="utf-8"))
        results = []
        for entry in manifest["documents"]:
            spec = DocSpec.from_manifest(entry)
            product_id = product_ids_by_sku.get(spec.product_sku) if spec.product_sku else None
            if spec.product_sku and product_id is None:
                raise ValueError(f"{spec.source}: unknown productSku {spec.product_sku}")
            results.append(await self.ingest(spec, (directory / spec.file).read_bytes(), product_id=product_id, force=force))
        return results

    async def remove(self, source: str) -> bool:
        deleted = await self._store.pool.fetchval("DELETE FROM documents WHERE source=$1 RETURNING 1", source)
        return bool(deleted)
