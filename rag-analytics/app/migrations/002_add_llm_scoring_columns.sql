-- Migration: Add LLM-as-Judge columns to query_events
-- Run this on existing databases; new databases get them via create_all().

ALTER TABLE query_events
  ADD COLUMN context_docs TEXT DEFAULT NULL AFTER post_checks_ms,
  ADD COLUMN llm_scored TINYINT(1) NOT NULL DEFAULT 0 AFTER context_docs,
  ADD COLUMN judge_explanations TEXT DEFAULT NULL AFTER llm_scored;

CREATE INDEX idx_llm_scored ON query_events (llm_scored);
CREATE INDEX idx_llm_scored_created ON query_events (llm_scored, created_at DESC);
