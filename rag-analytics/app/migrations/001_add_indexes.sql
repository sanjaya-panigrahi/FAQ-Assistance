-- Add performance indexes for dashboard queries
-- This migration improves leaderboard and recent queries performance

CREATE INDEX IF NOT EXISTS idx_created_at_desc ON query_events(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_effective_score_desc ON query_events(effective_rag_score DESC);
CREATE INDEX IF NOT EXISTS idx_framework_pattern ON query_events(framework, rag_pattern);
CREATE INDEX IF NOT EXISTS idx_customer_status ON query_events(customer_id, status);
CREATE INDEX IF NOT EXISTS idx_latency_ms ON query_events(latency_ms);

-- Text indexes for query/response search (optional, for full-text search later)
CREATE INDEX IF NOT EXISTS idx_query_text ON query_events(query_text(100));
CREATE INDEX IF NOT EXISTS idx_response_text ON query_events(response_text(100));

-- Composite indexes for common filter combinations
CREATE INDEX IF NOT EXISTS idx_framework_created ON query_events(framework, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_pattern_score ON query_events(rag_pattern, effective_rag_score DESC);
CREATE INDEX IF NOT EXISTS idx_customer_created ON query_events(customer_id, created_at DESC);
