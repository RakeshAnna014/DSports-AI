from __future__ import annotations

import httpx
import pytest

from app.sourcing.fetch import FetchError, SafeFetcher
from tests.sourcing.fixtures import HOST, public_resolver, victor_handler


def fetcher(handler=None, hosts=(HOST,), **kw) -> SafeFetcher:
    kw.setdefault("min_interval_s", 0)
    return SafeFetcher(list(hosts), transport=httpx.MockTransport(handler or victor_handler()), resolver=public_resolver, **kw)


@pytest.mark.asyncio
async def test_fetches_an_allowed_page_and_records_the_request():
    f = fetcher()
    got = await f.get(f"https://{HOST}/page/distributors")
    assert got.status == 200 and "VICTOR India" in got.text() and f.requests >= 2          # robots.txt + the page


@pytest.mark.asyncio
@pytest.mark.parametrize("url,code", [("https://evil.example.org/x", "URL_NOT_ALLOWED"), ("http://127.0.0.1/x", "URL_NOT_ALLOWED"), ("file:///etc/passwd", "URL_NOT_ALLOWED"),
                                      (f"https://user:pw@{HOST}/x", "URL_NOT_ALLOWED"), (f"https://{HOST}:8443/x", "URL_NOT_ALLOWED")])
async def test_unsafe_urls_are_refused_before_any_request(url, code):
    calls: list[str] = []
    f = fetcher(victor_handler(calls=calls), respect_robots=False)
    with pytest.raises(FetchError) as e:
        await f.get(url)
    assert e.value.code == code and calls == []


@pytest.mark.asyncio
async def test_private_address_behind_an_allowed_name_is_refused():
    f = SafeFetcher([HOST], transport=httpx.MockTransport(victor_handler()), resolver=lambda h: ["10.1.2.3"], min_interval_s=0, respect_robots=False)
    with pytest.raises(FetchError) as e:
        await f.get(f"https://{HOST}/page/distributors")
    assert e.value.code == "URL_NOT_ALLOWED" and "private" in e.value.message


@pytest.mark.asyncio
async def test_robots_disallowed_paths_are_not_fetched():
    calls: list[str] = []
    f = fetcher(victor_handler(calls=calls))
    with pytest.raises(FetchError) as e:
        await f.get(f"https://{HOST}/account/orders")
    assert e.value.code == "ROBOTS_DISALLOWED" and not any("/account" in c for c in calls)


@pytest.mark.asyncio
async def test_unreadable_robots_means_not_crawlable_but_a_missing_one_means_allowed():
    def handler(request):
        if request.url.path == "/robots.txt":
            return httpx.Response(503)
        return httpx.Response(200, text="ok", headers={"content-type": "text/html"})
    with pytest.raises(FetchError) as e:
        await fetcher(handler).get(f"https://{HOST}/x")
    assert e.value.code == "ROBOTS_DISALLOWED"

    def missing(request):
        return httpx.Response(404) if request.url.path == "/robots.txt" else httpx.Response(200, text="ok", headers={"content-type": "text/html"})
    assert (await fetcher(missing).get(f"https://{HOST}/x")).status == 200


@pytest.mark.asyncio
async def test_redirects_are_followed_by_hand_and_every_hop_is_checked():
    def handler(request):
        if request.url.path == "/robots.txt":
            return httpx.Response(404)
        if request.url.path == "/a":
            return httpx.Response(302, headers={"location": f"https://{HOST}/b"})
        if request.url.path == "/evil":
            return httpx.Response(302, headers={"location": "http://169.254.169.254/latest/meta-data"})
        return httpx.Response(200, text="final", headers={"content-type": "text/html"})
    f = fetcher(handler)
    assert (await f.get(f"https://{HOST}/a")).final_url.endswith("/b")
    with pytest.raises(FetchError) as e:
        await f.get(f"https://{HOST}/evil")
    assert e.value.code == "URL_NOT_ALLOWED" and "redirect target" in e.value.message


@pytest.mark.asyncio
async def test_redirect_loops_are_bounded():
    def handler(request):
        return httpx.Response(404) if request.url.path == "/robots.txt" else httpx.Response(302, headers={"location": f"https://{HOST}/loop"})
    with pytest.raises(FetchError) as e:
        await fetcher(handler, max_redirects=2).get(f"https://{HOST}/loop")
    assert e.value.code == "REDIRECT_BLOCKED"


@pytest.mark.asyncio
async def test_size_type_status_and_timeout_are_reported_with_stable_codes():
    def handler(request):
        p = request.url.path
        if p == "/robots.txt":
            return httpx.Response(404)
        if p == "/big":
            return httpx.Response(200, content=b"x" * 5000, headers={"content-type": "text/html", "content-length": "5000"})
        if p == "/json":
            return httpx.Response(200, content=b"{}", headers={"content-type": "application/json"})
        if p == "/slow":
            raise httpx.ReadTimeout("slow")
        return httpx.Response(500)
    f = fetcher(handler, max_bytes=1000)
    for path, code in (("/big", "TOO_LARGE"), ("/json", "BAD_CONTENT_TYPE"), ("/slow", "TIMEOUT"), ("/err", "HTTP_STATUS")):
        with pytest.raises(FetchError) as e:
            await f.get(f"https://{HOST}{path}")
        assert e.value.code == code, path


@pytest.mark.asyncio
async def test_requests_to_one_host_are_spaced_out():
    waits: list[float] = []

    async def fake_sleep(s):
        waits.append(s)
    f = SafeFetcher([HOST], transport=httpx.MockTransport(victor_handler()), resolver=public_resolver, min_interval_s=5.0, respect_robots=False, sleep=fake_sleep)
    await f.get(f"https://{HOST}/page/distributors")
    await f.get(f"https://{HOST}/page/distributors")
    assert waits and max(waits) > 0 and max(waits) <= 5.0
