"""The only way Product & Supplier Intelligence reads an external URL.

Every request: the host must be on an allow-list; the URL is checked for scheme, credentials, port and private/loopback/link-local/reserved addresses (SSRF); redirects are followed by hand, up to a
small limit, and EVERY hop is checked again; the response is streamed and cut at a size limit; the content type must be one the caller expects; there is a timeout; requests to one host are spaced
out; and robots.txt is honoured (a path the site disallows for crawlers is not fetched). A failure is an explicit FetchError with a stable code, never an empty success.

DNS rebinding: check_url resolves the name to decide early, but the connection itself goes through PinnedTransport, which resolves the name again, requires every address to be public and connects
to that exact address (with the real Host header and TLS server name). A name that changes its answer between the check and the connection therefore cannot reach an internal address. Allow-list only hosts you trust.
"""
from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from typing import Callable, Sequence
from urllib.parse import urljoin, urlsplit
from urllib.robotparser import RobotFileParser

import httpx

from app.sourcing.normalize import UnsafeUrl, check_url, resolve_public

log = logging.getLogger(__name__)
DEFAULT_UA = "DSports-ProductIntelligence/1.0 (+buyer research; respects robots.txt)"
REDIRECTS = (301, 302, 303, 307, 308)
HTML_TYPES = ("text/html", "application/xhtml+xml")
XML_TYPES = ("application/xml", "text/xml", "application/rss+xml")
IMAGE_TYPES = ("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif")


class FetchError(Exception):
    def __init__(self, code: str, message: str, status: int | None = None):
        super().__init__(message)
        self.code, self.message, self.status = code, message, status


@dataclass
class Fetched:
    url: str
    final_url: str
    status: int
    content_type: str
    body: bytes
    truncated: bool
    declared_length: int | None = None

    def text(self) -> str:
        return self.body.decode("utf-8", errors="replace")


class PinnedTransport(httpx.AsyncBaseTransport):
    """Connects to the address that was just validated, not to whatever the name resolves to a moment later.

    The name is resolved HERE, every returned address must be public, and the request is sent to one of those exact addresses with the original Host header and TLS server name, so the
    certificate is still checked against the real host. A DNS-rebinding answer that changes between the check and the connection therefore cannot redirect the request."""

    def __init__(self, inner: httpx.AsyncBaseTransport | None = None, resolver: Callable[[str], list[str]] | None = None):
        self.inner, self.resolver = inner or httpx.AsyncHTTPTransport(), resolver

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        host = request.url.host
        try:
            addresses = await asyncio.get_running_loop().run_in_executor(None, resolve_public, host, self.resolver)
        except UnsafeUrl as exc:
            raise httpx.ConnectError(f"refused: {exc}") from None
        ip = addresses[0]
        netloc_host = f"[{ip}]" if ":" in ip else ip
        headers = httpx.Headers(request.headers)
        pinned = httpx.Request(request.method, request.url.copy_with(host=ip), headers=headers, stream=request.stream, extensions={**request.extensions, "sni_hostname": host})
        del netloc_host
        return await self.inner.handle_async_request(pinned)

    async def aclose(self) -> None:
        await self.inner.aclose()


class SafeFetcher:
    def __init__(self, allowed_hosts: Sequence[str], *, timeout_s: float = 15.0, max_bytes: int = 2_000_000, user_agent: str = DEFAULT_UA, min_interval_s: float = 1.0,
                 transport: httpx.AsyncBaseTransport | None = None, resolver: Callable[[str], list[str]] | None = None, max_redirects: int = 3, respect_robots: bool = True,
                 sleep: Callable[[float], "asyncio.Future"] | None = None):
        self.allowed, self.timeout, self.max_bytes, self.ua = [h.strip().lower() for h in allowed_hosts if h.strip()], timeout_s, max_bytes, user_agent
        self.min_interval, self.transport, self.resolver, self.max_redirects, self.respect_robots = min_interval_s, transport, resolver, max_redirects, respect_robots
        self._last: dict[str, float] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self._robots: dict[str, RobotFileParser | None] = {}
        self._sleep = sleep or asyncio.sleep
        self.requests = 0

    def allows_host(self, url: str) -> bool:
        try:
            check_url(url, self.allowed, self.resolver)
            return True
        except UnsafeUrl:
            return False

    async def _polite(self, host: str) -> None:
        lock = self._locks.setdefault(host, asyncio.Lock())
        async with lock:
            wait = self._last.get(host, 0.0) + self.min_interval - time.monotonic()
            if wait > 0:
                await self._sleep(wait)
            self._last[host] = time.monotonic()

    async def _request(self, url: str, accept: Sequence[str], max_bytes: int, headers: dict[str, str] | None = None, peek: bool = False) -> Fetched:
        current = url
        for hop in range(self.max_redirects + 1):
            try:
                check_url(current, self.allowed, self.resolver)
            except UnsafeUrl as exc:
                raise FetchError("URL_NOT_ALLOWED", f"{'redirect target' if hop else 'URL'} refused: {exc}") from None
            host = (urlsplit(current).hostname or "").lower()
            await self._polite(host)
            self.requests += 1
            try:
                async with httpx.AsyncClient(timeout=self.timeout, follow_redirects=False, transport=self.transport or PinnedTransport(resolver=self.resolver), headers={"User-Agent": self.ua, "Accept-Language": "en", **(headers or {})}) as client:
                    async with client.stream("GET", current) as resp:
                        if resp.status_code in REDIRECTS and resp.headers.get("location"):
                            current = urljoin(current, resp.headers["location"])
                            continue
                        if resp.status_code != 200:
                            raise FetchError("HTTP_STATUS", f"the source answered HTTP {resp.status_code}", resp.status_code)
                        ctype = resp.headers.get("content-type", "").split(";")[0].strip().lower()
                        if accept and not any(ctype.startswith(a) for a in accept):
                            raise FetchError("BAD_CONTENT_TYPE", f"unexpected content type {ctype or 'unknown'}")
                        declared = resp.headers.get("content-length")
                        if declared and declared.isdigit() and int(declared) > max_bytes and not peek:
                            raise FetchError("TOO_LARGE", f"the response ({declared} bytes) is larger than the {max_bytes} byte limit")
                        body, truncated = b"", False
                        async for chunk in resp.aiter_bytes():
                            body += chunk
                            if len(body) > max_bytes:
                                body, truncated = body[:max_bytes], True
                                break
                        return Fetched(url, current, resp.status_code, ctype, body, truncated, int(declared) if declared and declared.isdigit() else None)
            except FetchError:
                raise
            except httpx.TimeoutException:
                raise FetchError("TIMEOUT", "the source timed out") from None
            except httpx.HTTPError as exc:
                raise FetchError("NETWORK", f"the source could not be reached ({type(exc).__name__})") from None
        raise FetchError("REDIRECT_BLOCKED", f"more than {self.max_redirects} redirects")

    async def robots_allows(self, url: str) -> bool:
        parts = urlsplit(url)
        origin = f"{parts.scheme}://{parts.netloc}"
        if origin not in self._robots:
            parser: RobotFileParser | None = RobotFileParser()
            try:
                got = await self._request(f"{origin}/robots.txt", ("text/plain", "text/html", "application/octet-stream", ""), 200_000)
                parser.parse(got.text().splitlines())          # type: ignore[union-attr]
            except FetchError as exc:
                if exc.status and 400 <= exc.status < 500:
                    parser.parse([])                           # no robots.txt: everything is allowed (RFC 9309)
                else:
                    log.info("robots.txt unavailable for %s (%s): treating the site as not crawlable for this run", origin, exc.code)
                    parser = None
            self._robots[origin] = parser
        parser = self._robots[origin]
        return bool(parser and parser.can_fetch(self.ua, url))

    async def get(self, url: str, accept: Sequence[str] = HTML_TYPES, max_bytes: int | None = None, robots: bool | None = None, headers: dict[str, str] | None = None,
                  peek: bool = False) -> Fetched:
        if (self.respect_robots if robots is None else robots) and not await self.robots_allows(url):
            raise FetchError("ROBOTS_DISALLOWED", "the site's robots.txt does not allow automated access to this page (or could not be read)")
        return await self._request(url, accept, max_bytes or self.max_bytes, headers, peek)
