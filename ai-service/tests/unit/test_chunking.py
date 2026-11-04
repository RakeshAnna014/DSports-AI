import pytest

from app.rag.chunking import chunk_text, count_tokens
from app.rag.tika import html_to_pages
from app.rag.retrieval import or_tsquery, rrf_fuse
from app.rag.embedder import content_hash, to_pgvector
from app.rag.ingest import clean_text

DOC = """# Guide

Intro paragraph about the topic. It has two sentences.

## Weight

A lighter bat is easier to swing. A heavier bat carries more power.

## Care

Oil the bat lightly. Store it dry.
"""


def long_paragraph(sentences: int) -> str:
    return " ".join(f"Sentence number {i} talks about cricket bats and how to choose one." for i in range(sentences))


def test_small_document_is_one_chunk_with_headings_kept():
    chunks = chunk_text(DOC, 450, 60)
    assert len(chunks) == 1
    assert "## Weight" in chunks[0].text and "## Care" in chunks[0].text
    assert chunks[0].section == "Guide"


def test_chunks_never_exceed_the_target_size():
    chunks = chunk_text(long_paragraph(120), target_tokens=120, overlap_tokens=20)
    assert len(chunks) > 3
    assert all(c.tokens <= 120 + 20 for c in chunks)  # target plus the carried overlap


def test_overlap_repeats_the_tail_of_the_previous_chunk():
    chunks = chunk_text(long_paragraph(60), target_tokens=100, overlap_tokens=25)
    for previous, current in zip(chunks, chunks[1:]):
        tail = previous.text[-60:].split(" ", 1)[-1]
        assert tail[:25].strip() in current.text


def test_no_overlap_when_zero():
    chunks = chunk_text(long_paragraph(60), target_tokens=100, overlap_tokens=0)
    joined = " ".join(c.text for c in chunks)
    assert joined.count("Sentence number 10 ") == 1


def test_headings_start_a_new_section_path():
    text = "# A\n\nalpha " * 1 + "word " * 400 + "\n\n## B\n\nbeta " + "word " * 400
    chunks = chunk_text(text, 200, 20)
    assert chunks[0].section == "A"
    assert any(c.section and c.section.endswith("B") for c in chunks)


def test_a_single_enormous_sentence_is_split_on_words():
    chunks = chunk_text("word " * 2000, 100, 10)
    assert len(chunks) > 10 and all(c.tokens <= 130 for c in chunks)


def test_chunking_is_deterministic_and_indexed_from_zero():
    a, b = chunk_text(long_paragraph(80), 150, 30), chunk_text(long_paragraph(80), 150, 30)
    assert [c.text for c in a] == [c.text for c in b]
    assert [c.index for c in a] == list(range(len(a)))


def test_empty_text_has_no_chunks():
    assert chunk_text("  \n\n  ") == []


def test_overlap_must_be_smaller_than_chunk():
    with pytest.raises(ValueError):
        chunk_text("x", 50, 50)


def test_token_estimate():
    assert count_tokens("") == 0
    assert count_tokens("abcd") == 1 and count_tokens("abcde") == 2


# ---------------------------------------------------------------- tika html -> text

def test_tika_html_headings_and_lists_become_markdown():
    html = "<html><body><h1>Title</h1><p>Para one. Two.</p><h2>Sub</h2><ul><li>a</li><li>b</li></ul></body></html>"
    assert html_to_pages(html) == ["# Title\n\nPara one. Two.\n\n## Sub\n\n- a\n- b"]


def test_tika_pdf_pages_are_split():
    html = '<body><div class="page"><p>First page</p></div><div class="page"><p>Second page</p></div></body>'
    assert html_to_pages(html) == ["First page", "Second page"]


def test_tika_entities_are_decoded():
    assert html_to_pages("<body><p>Tom &amp; Jerry &lt;3</p></body>") == ["Tom & Jerry <3"]


def test_clean_text_repairs_pdf_artefacts():
    assert clean_text("war-\nranty covers\x00 defects\n\n\n\nnext") == "warranty covers defects\n\nnext"


# ---------------------------------------------------------------- retrieval maths

def test_rrf_rewards_agreement_between_lists():
    fused = rrf_fuse([["a", "b", "c"], ["b", "a", "d"]])
    assert fused["a"] == pytest.approx(1 / 61 + 1 / 62) and fused["b"] == pytest.approx(1 / 62 + 1 / 61)
    assert fused["c"] < fused["a"] and fused["d"] < fused["a"]


def test_rrf_item_in_one_list_still_scores():
    assert rrf_fuse([["a"], []]) == {"a": pytest.approx(1 / 61)}


def test_or_tsquery_builds_a_safe_or_query():
    assert or_tsquery("How should I maintain this bat?") == "how | should | maintain | this | bat"
    assert or_tsquery("a b ??") is None
    assert "'" not in (or_tsquery("bat'; DROP TABLE x; --") or "")


def test_pgvector_literal_and_hashes():
    assert to_pgvector([0.5, -1.0, 2.25]) == "[0.5,-1,2.25]"
    assert content_hash("a", "b") == content_hash("a", "b") != content_hash("ab", "")
