import httpx
import pytest

from app.rag.tika import ExtractionError, TikaClient


def tika(handler, min_chars=50):
    return TikaClient("http://tika.test", min_chars_per_page=min_chars, http=httpx.AsyncClient(transport=httpx.MockTransport(handler)))


def respond(meta, status=200):
    return lambda request: httpx.Response(status, json=[meta]) if status == 200 else httpx.Response(status, text="x")


PAGE = '<div class="page"><p>{}</p></div>'


async def test_digital_pdf_is_read_without_ocr():
    html = "<body>" + PAGE.format("A" * 400) + PAGE.format("B" * 300) + "</body>"
    meta = {"tk:content": html, "Content-Type": "application/pdf", "pdf:chars-per-page": ["400", "300"], "pdf:ocr-page-count": "0"}
    e = await tika(respond(meta)).extract(b"%PDF", "application/pdf")
    assert e.ocr_used is False and e.is_scan is False and e.digital_chars_per_page == 350 and len(e.pages) == 2


async def test_scanned_pdf_is_detected_from_the_empty_text_layer_and_ocr_is_recorded():
    html = "<body>" + PAGE.format("Warranty 90 days") + PAGE.format("Claim within 7 days") + "</body>"
    meta = {"tk:content": html, "Content-Type": "application/pdf", "pdf:chars-per-page": ["0", "0"], "pdf:ocr-page-count": "2"}
    e = await tika(respond(meta)).extract(b"%PDF", "application/pdf")
    assert e.is_scan is True and e.ocr_used is True and e.ocr_pages == 2 and e.pages == ["Warranty 90 days", "Claim within 7 days"]


async def test_a_scan_that_yields_no_text_is_refused():
    meta = {"tk:content": "<body>" + PAGE.format("") + "</body>", "Content-Type": "application/pdf", "pdf:chars-per-page": ["0"], "pdf:ocr-page-count": "0"}
    with pytest.raises(ExtractionError) as e:
        await tika(respond(meta)).extract(b"%PDF", "application/pdf")
    assert e.value.code == "NO_TEXT"


async def test_the_scan_threshold_is_configurable():
    meta = {"tk:content": "<body>" + PAGE.format("x" * 30) + "</body>", "Content-Type": "application/pdf", "pdf:chars-per-page": ["30"], "pdf:ocr-page-count": "0"}
    assert (await tika(respond(meta), min_chars=50).extract(b"%PDF", "application/pdf")).is_scan is True
    assert (await tika(respond(meta), min_chars=10).extract(b"%PDF", "application/pdf")).is_scan is False


async def test_markdown_has_no_ocr_step_and_keeps_headings():
    meta = {"tk:content": "<body><h1>Care</h1><p>Oil lightly.</p></body>", "Content-Type": "text/markdown; charset=UTF-8"}
    e = await tika(respond(meta)).extract(b"# Care", "text/markdown")
    assert e.pages == ["# Care\n\nOil lightly."] and e.ocr_used is False and e.is_scan is False


async def test_images_are_ocrd():
    meta = {"tk:content": "<body><p>Text in a photo</p></body>", "Content-Type": "image/png"}
    assert (await tika(respond(meta)).extract(b"\x89PNG", "image/png")).ocr_used is True


async def test_text_types_are_sent_as_utf8():
    seen = {}
    def handler(request):
        seen["content_type"] = request.headers["content-type"]
        return httpx.Response(200, json=[{"tk:content": "<body><p>x</p></body>", "Content-Type": "text/markdown"}])
    await tika(handler).extract("café".encode(), "text/markdown")
    assert seen["content_type"] == "text/markdown; charset=utf-8"


async def test_errors_are_mapped_to_stable_codes():
    with pytest.raises(ExtractionError) as e:
        await tika(respond({}, 422)).extract(b"x", "application/pdf")
    assert e.value.code == "UNSUPPORTED_DOCUMENT"
    with pytest.raises(ExtractionError) as e:
        await tika(respond({}, 500)).extract(b"x", "application/pdf")
    assert e.value.code == "TIKA_ERROR"
    def boom(request):
        raise httpx.ConnectError("refused")
    with pytest.raises(ExtractionError) as e:
        await tika(boom).extract(b"x", "application/pdf")
    assert e.value.code == "TIKA_UNAVAILABLE"
