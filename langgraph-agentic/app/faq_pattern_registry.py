"""
FAQ Pattern Registry - Scalable pattern matching for structured FAQ extraction.

Provides a configuration-driven system to extract structured answers from FAQ context
without hardcoding patterns into each service.
"""

import re
from pathlib import Path
from typing import Optional

import yaml


class FAQPatternRegistry:
    """Registry for FAQ extraction patterns with intelligent classification."""

    def __init__(self, config_path: Optional[str] = None):
        self.patterns = []
        self.config_path = self._find_config(config_path)
        self._load_patterns()

    def _find_config(self, explicit_path: Optional[str]) -> str:
        if explicit_path and Path(explicit_path).exists():
            return explicit_path

        search_paths = [
            Path(__file__).parent / "patterns_config.yaml",
            Path("/app/patterns_config.yaml"),
            Path("./shared-patterns/patterns_config.yaml"),
            Path("./patterns_config.yaml"),
        ]

        for path in search_paths:
            if path.exists():
                return str(path)

        raise FileNotFoundError("patterns_config.yaml not found in standard locations")

    def _load_patterns(self) -> None:
        with open(self.config_path, "r", encoding="utf-8") as f:
            config = yaml.safe_load(f)
            self.patterns = sorted(
                config.get("patterns", []), key=lambda p: p.get("priority", 0), reverse=True
            )

    def classify_question(self, question: str) -> Optional[str]:
        if not question:
            return None

        q_lower = question.lower()
        for pattern in self.patterns:
            keywords = pattern.get("keywords", [])
            if all(kw in q_lower for kw in keywords):
                return pattern.get("id")
        return None

    def extract_answer(self, context: str, pattern_id: str) -> Optional[str]:
        if not context or not pattern_id:
            return None

        pattern = next((p for p in self.patterns if p.get("id") == pattern_id), None)
        if not pattern:
            return None

        regex = pattern.get("regex")
        if not regex:
            return None

        match = re.search(regex, context, re.IGNORECASE)
        if not match:
            return None

        format_str = pattern.get("format_string", "{1}")
        for i, group in enumerate(match.groups(), 1):
            if group:
                format_str = format_str.replace(f"{{{i}}}", str(group))
        return format_str

    def extract_faq_answer(self, question: str, context: str) -> Optional[str]:
        pattern_id = self.classify_question(question)
        if not pattern_id:
            return None
        return self.extract_answer(context, pattern_id)


_registry: Optional[FAQPatternRegistry] = None


def get_registry() -> FAQPatternRegistry:
    global _registry
    if _registry is None:
        _registry = FAQPatternRegistry()
    return _registry


def extract_faq_answer(question: str, context: str) -> Optional[str]:
    return get_registry().extract_faq_answer(question, context)
