from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field


class QueryMetricIn(BaseModel):
    requestId: str = Field(min_length=1, max_length=64)
    mode: str = Field(default="single")
    query: str
    response: str
    customer: str
    ragPattern: str
    framework: str
    strategy: str = ""
    status: str = "success"
    latencyMs: int = Field(ge=0)

    retrievalQuality: Optional[float] = Field(default=None, ge=0, le=1)
    groundedCorrectness: Optional[float] = Field(default=None, ge=0, le=1)
    safety: Optional[float] = Field(default=None, ge=0, le=1)
    latencyEfficiency: Optional[float] = Field(default=None, ge=0, le=1)

    queryParseMs: Optional[int] = Field(default=None, ge=0)
    retrievalMs: Optional[int] = Field(default=None, ge=0)
    rerankMs: Optional[int] = Field(default=None, ge=0)
    generationMs: Optional[int] = Field(default=None, ge=0)
    postChecksMs: Optional[int] = Field(default=None, ge=0)

    contextDocs: Optional[str] = Field(default=None, description="Retrieved context documents for LLM scoring")


class QueryMetricBatchIn(BaseModel):
    events: list[QueryMetricIn]


class LeaderboardItem(BaseModel):
    framework: str
    ragPattern: str
    totalRuns: int
    successRate: float
    avgLatencyMs: float
    avgEffectiveRagScore: float


class RecentRun(BaseModel):
    createdAt: datetime
    framework: str
    ragPattern: str
    customer: str
    query: str
    status: str
    latencyMs: int
    effectiveRagScore: float
    strategy: str
    llmScored: bool = False
    retrievalQuality: Optional[float] = None
    groundedCorrectness: Optional[float] = None
    safety: Optional[float] = None
    latencyEfficiency: Optional[float] = None
    judgeExplanations: Optional[dict] = None


class ScoreDistributionBucket(BaseModel):
    bucket: str
    count: int


class SubScoreBreakdown(BaseModel):
    framework: str
    ragPattern: str
    avgRetrievalQuality: float
    avgGroundedCorrectness: float
    avgSafety: float
    avgLatencyEfficiency: float
    avgEffectiveRagScore: float
    llmScoredCount: int
    heuristicCount: int


class ScoreDistributionResponse(BaseModel):
    distribution: list[ScoreDistributionBucket]
    subscoreBreakdown: list[SubScoreBreakdown]


class DashboardResponse(BaseModel):
    leaderboard: list[LeaderboardItem]
    recent: list[RecentRun]
