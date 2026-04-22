"""
FAQ Pattern Registry - Scalable pattern matching for structured FAQ extraction.

Provides a configuration-driven system to extract structured answers from FAQ context
without hardcoding patterns into each service.
"""

import re
from typing import Optional
from pathlib import Path
import yaml


class FAQPatternRegistry:
    """Registry for FAQ extraction patterns with intelligent classification."""
    
    def __init__(self, config_path: Optional[str] = None):
        """
        Initialize pattern registry from YAML config.
        
        Args:
            config_path: Path to patterns_config.yaml. If None, searches standard locations.
        """
        self.patterns = []
        self.config_path = self._find_config(config_path)
        self._load_patterns()
    
    def _find_config(self, explicit_path: Optional[str]) -> str:
        """Find patterns config file."""
        if explicit_path and Path(explicit_path).exists():
            return explicit_path
        
        # Search in common locations
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
        """Load pattern definitions from YAML config."""
        try:
            with open(self.config_path, 'r') as f:
                config = yaml.safe_load(f)
                # Sort by priority descending
                self.patterns = sorted(
                    config.get("patterns", []),
                    key=lambda p: p.get("priority", 0),
                    reverse=True
                )
        except Exception as e:
            raise RuntimeError(f"Failed to load patterns config from {self.config_path}: {e}")
    
    def classify_question(self, question: str) -> Optional[str]:
        """
        Classify question to determine which pattern applies.
        
        Returns pattern ID if matched, None otherwise.
        """
        if not question:
            return None
        
        q_lower = question.lower()
        
        # Match against keywords in priority order
        for pattern in self.patterns:
            keywords = pattern.get("keywords", [])
            # All keywords must be present
            if all(kw in q_lower for kw in keywords):
                return pattern.get("id")
        
        return None
    
    def extract_answer(self, context: str, pattern_id: str) -> Optional[str]:
        """
        Extract answer from context using specified pattern.
        
        Args:
            context: FAQ context text
            pattern_id: Pattern identifier to use
            
        Returns:
            Formatted answer if pattern matches, None otherwise.
        """
        if not context or not pattern_id:
            return None
        
        pattern = next((p for p in self.patterns if p.get("id") == pattern_id), None)
        if not pattern:
            return None
        
        regex = pattern.get("regex")
        if not regex:
            return None
        
        try:
            match = re.search(regex, context, re.IGNORECASE)
            if not match:
                return None
            
            # Format answer with captured groups
            format_str = pattern.get("format_string", "{1}")
            for i, group in enumerate(match.groups(), 1):
                if group:
                    format_str = format_str.replace(f"{{{i}}}", str(group))
            
            return format_str
        except Exception as e:
            return None
    
    def extract_faq_answer(self, question: str, context: str) -> Optional[str]:
        """
        Universal FAQ answer extraction - intelligent pattern matching.
        
        Workflow:
        1. Classify question to determine pattern type
        2. Apply regex extraction using that pattern
        3. Format and return answer if matched
        
        Args:
            question: User question
            context: FAQ context to search
            
        Returns:
            Extracted/formatted answer, or None if no pattern matches.
        """
        pattern_id = self.classify_question(question)
        if not pattern_id:
            return None
        
        return self.extract_answer(context, pattern_id)
    
    def get_pattern_by_id(self, pattern_id: str) -> Optional[dict]:
        """Get pattern definition by ID."""
        return next((p for p in self.patterns if p.get("id") == pattern_id), None)
    
    def list_patterns(self) -> list:
        """List all available patterns with metadata."""
        return [
            {
                "id": p.get("id"),
                "keywords": p.get("keywords"),
                "priority": p.get("priority"),
                "description": p.get("description"),
            }
            for p in self.patterns
        ]


# Global registry instance
_registry: Optional[FAQPatternRegistry] = None


def get_registry() -> FAQPatternRegistry:
    """Get or initialize global pattern registry (singleton)."""
    global _registry
    if _registry is None:
        _registry = FAQPatternRegistry()
    return _registry


def extract_faq_answer(question: str, context: str) -> Optional[str]:
    """
    Module-level convenience function for FAQ answer extraction.
    
    Usage in LangChain tools:
        from langchain.tools import tool
        
        @tool
        def extract_faq_answer_tool(question: str, context: str) -> str:
            result = extract_faq_answer(question, context)
            return result or "No structured answer found."
    """
    return get_registry().extract_faq_answer(question, context)
