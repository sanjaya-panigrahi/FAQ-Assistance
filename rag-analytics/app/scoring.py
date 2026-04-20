from .schemas import QueryMetricIn


def clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


def derive_latency_efficiency(latency_ms: int) -> float:
    if latency_ms <= 0:
        return 0.0
    # 1.2s is considered ideal median from architecture notes.
    return clamp01(1200.0 / float(latency_ms))


def derive_subscores(event: QueryMetricIn) -> tuple[float, float, float, float]:
    retrieval_quality = event.retrievalQuality if event.retrievalQuality is not None else (0.7 if event.status == "success" else 0.2)
    grounded_correctness = event.groundedCorrectness if event.groundedCorrectness is not None else (0.75 if event.status == "success" else 0.25)

    if event.safety is not None:
        safety = event.safety
    else:
        response_lower = (event.response or "").lower()
        safety = 0.55 if ("unsafe" in response_lower or "blocked" in response_lower) else 0.9

    latency_efficiency = event.latencyEfficiency if event.latencyEfficiency is not None else derive_latency_efficiency(event.latencyMs)

    return (
        clamp01(retrieval_quality),
        clamp01(grounded_correctness),
        clamp01(safety),
        clamp01(latency_efficiency),
    )


def derive_effective_score(retrieval_quality: float, grounded_correctness: float, safety: float, latency_efficiency: float) -> float:
    score = (
        0.35 * retrieval_quality
        + 0.30 * grounded_correctness
        + 0.20 * safety
        + 0.15 * latency_efficiency
    )
    return round(clamp01(score), 4)


def derive_phase_timings(event: QueryMetricIn) -> tuple[int, int, int, int, int]:
    if (
        event.queryParseMs is not None
        and event.retrievalMs is not None
        and event.rerankMs is not None
        and event.generationMs is not None
        and event.postChecksMs is not None
    ):
        return (
            event.queryParseMs,
            event.retrievalMs,
            event.rerankMs,
            event.generationMs,
            event.postChecksMs,
        )

    base_total = 40 + 120 + 160 + 800 + 80
    latency = max(event.latencyMs, 1)
    scale = latency / base_total

    return (
        int(round(40 * scale)),
        int(round(120 * scale)),
        int(round(160 * scale)),
        int(round(800 * scale)),
        int(round(80 * scale)),
    )
