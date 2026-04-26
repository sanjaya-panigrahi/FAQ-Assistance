"""Unit tests for enhanced pipeline features: MultiQuery, Ensemble+BM25, CohereRerank, Adaptive-k."""
import os
import sys
from unittest.mock import MagicMock, patch

import pytest
from langchain_core.documents import Document

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

# Pipeline is already importable because conftest mocks heavy deps
from app.pipeline import RetrievalPipeline


# ---------- Fixtures ----------

@pytest.fixture
def sample_documents():
    return [
        Document(
            page_content="How do I return a product? You can return within 30 days.",
            metadata={"source": "faq.md", "chunk_number": 1, "vector_score": 0.9},
        ),
        Document(
            page_content="What is the shipping cost? Shipping is free over $50.",
            metadata={"source": "faq.md", "chunk_number": 2, "vector_score": 0.7},
        ),
        Document(
            page_content="Do you offer gift cards? Yes, digital gift cards are available.",
            metadata={"source": "faq.md", "chunk_number": 3, "vector_score": 0.4},
        ),
    ]


@pytest.fixture
def duplicate_documents():
    return [
        Document(page_content="Return policy info", metadata={"source": "faq.md", "chunk_number": 1}),
        Document(page_content="Return policy info", metadata={"source": "faq.md", "chunk_number": 1}),
        Document(page_content="Shipping details here", metadata={"source": "faq.md", "chunk_number": 2}),
    ]


@pytest.fixture
def pipeline_instance():
    p = RetrievalPipeline()
    p._llm = MagicMock()
    return p


# ---------- MultiQuery ----------

class TestMultiQueryExpand:
    def test_returns_original_plus_variants(self, pipeline_instance):
        mock_result = MagicMock()
        mock_result.content = "What is the return process?\nHow can I send items back?\nReturn procedure?"
        pipeline_instance._llm = MagicMock()
        pipeline_instance._llm.invoke.return_value = mock_result

        variants = pipeline_instance._multi_query_expand("How do I return?", None)

        assert variants[0] == "How do I return?"
        assert len(variants) >= 2

    def test_falls_back_on_llm_error(self, pipeline_instance):
        pipeline_instance._llm = MagicMock()
        pipeline_instance._llm.invoke.side_effect = RuntimeError("API error")

        variants = pipeline_instance._multi_query_expand("How do I return?", None)

        assert variants == ["How do I return?"]

    def test_includes_query_context(self, pipeline_instance):
        mock_result = MagicMock()
        mock_result.content = "variant 1\nvariant 2"
        pipeline_instance._llm = MagicMock()
        pipeline_instance._llm.invoke.return_value = mock_result

        variants = pipeline_instance._multi_query_expand("return policy", "electronics")

        assert variants[0] == "return policy electronics"


# ---------- Deduplicate ----------

class TestDeduplicate:
    def test_removes_exact_duplicates(self, pipeline_instance, duplicate_documents):
        result = pipeline_instance._deduplicate(duplicate_documents)
        assert len(result) == 2

    def test_keeps_unique_docs(self, pipeline_instance, sample_documents):
        result = pipeline_instance._deduplicate(sample_documents)
        assert len(result) == 3


# ---------- CohereRerank ----------

class TestCohereRerank:
    def test_returns_reranked_documents(self, pipeline_instance, sample_documents):
        with patch("langchain_cohere.CohereRerank") as MockCohere:
            mock_compressor = MagicMock()
            reranked = [sample_documents[0], sample_documents[2]]
            for doc in reranked:
                doc.metadata["relevance_score"] = 0.95
            mock_compressor.compress_documents.return_value = reranked
            MockCohere.return_value = mock_compressor

            result = pipeline_instance._cohere_rerank("return policy", sample_documents, top_n=2)

            assert len(result) == 2
            assert result[0].metadata["relevance_score"] == 0.95

    def test_falls_back_on_cohere_error(self, pipeline_instance, sample_documents):
        with patch("langchain_cohere.CohereRerank") as MockCohere:
            MockCohere.return_value.compress_documents.side_effect = RuntimeError("API error")

            result = pipeline_instance._cohere_rerank("return policy", sample_documents)

            assert len(result) == len(sample_documents)

    def test_empty_documents(self, pipeline_instance):
        result = pipeline_instance._cohere_rerank("query", [])
        assert result == []


# ---------- Adaptive k ----------

class TestAdaptiveK:
    def test_high_confidence_returns_fewer(self, pipeline_instance, sample_documents):
        sample_documents[0].metadata["relevance_score"] = 0.9
        result = pipeline_instance._adaptive_k(sample_documents, top_k=10)
        assert len(result) <= 4

    def test_low_confidence_returns_more(self, pipeline_instance, sample_documents):
        sample_documents[0].metadata["relevance_score"] = 0.3
        result = pipeline_instance._adaptive_k(sample_documents, top_k=20)
        assert len(result) == len(sample_documents)  # only 3 docs available

    def test_empty_documents(self, pipeline_instance):
        result = pipeline_instance._adaptive_k([], top_k=6)
        assert result == []

    def test_respects_top_k_limit(self, pipeline_instance, sample_documents):
        sample_documents[0].metadata["relevance_score"] = 0.9
        result = pipeline_instance._adaptive_k(sample_documents, top_k=2)
        assert len(result) <= 2


# ---------- Hybrid Rerank Fallback ----------

class TestHybridRerank:
    def test_ranks_by_composite_score(self, pipeline_instance, sample_documents):
        result = pipeline_instance._hybrid_rerank("return product", sample_documents, 0.35)

        assert len(result) >= 1
        scores = [d.metadata["relevance_score"] for d in result]
        assert scores == sorted(scores, reverse=True)

    def test_empty_documents(self, pipeline_instance):
        result = pipeline_instance._hybrid_rerank("query", [], 0.35)
        assert result == []


# ---------- Strategy Label ----------

class TestStrategyLabel:
    @patch("app.pipeline.settings")
    def test_full_enhanced_strategy(self, mock_settings, pipeline_instance):
        mock_settings.enable_multi_query = True
        mock_settings.enable_ensemble_bm25 = True
        mock_settings.enable_cohere_rerank = True
        mock_settings.cohere_api_key = "test-key"
        mock_settings.enable_adaptive_k = True

        label = pipeline_instance._build_strategy_label()
        assert "multi-query" in label
        assert "ensemble(bm25+chroma)" in label
        assert "cohere-rerank" in label
        assert "adaptive-k" in label

    @patch("app.pipeline.settings")
    def test_fallback_strategy(self, mock_settings, pipeline_instance):
        mock_settings.enable_multi_query = False
        mock_settings.enable_ensemble_bm25 = False
        mock_settings.enable_cohere_rerank = False
        mock_settings.cohere_api_key = ""
        mock_settings.enable_adaptive_k = False

        label = pipeline_instance._build_strategy_label()
        assert "query-transform" in label
        assert "hybrid-retrieval" in label
        assert "rerank" in label


# ---------- Docs to Chunks ----------

class TestDocsToChunkDicts:
    def test_converts_documents_to_dicts(self, pipeline_instance, sample_documents):
        sample_documents[0].metadata["relevance_score"] = 0.85
        result = pipeline_instance._docs_to_chunk_dicts(sample_documents[:1])

        assert len(result) == 1
        assert result[0]["source"] == "faq.md"
        assert result[0]["chunk_number"] == 1
        assert result[0]["score"] == 0.85
        assert "return" in result[0]["content"].lower()
