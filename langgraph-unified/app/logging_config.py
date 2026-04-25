"""Structured logging configuration for ELK integration."""
import os
import sys
import logging
from datetime import datetime, timezone
from typing import Any, Dict

import structlog
from pythonjsonlogger import jsonlogger


def setup_logging(service_name: str = "faq-service", log_level: str = None) -> None:
    """Configure structured logging with JSON output for ELK."""
    if log_level is None:
        log_level = os.getenv("LOG_LEVEL", "INFO")
    
    # Configure structlog
    structlog.configure(
        processors=[
            structlog.stdlib.filter_by_level,
            structlog.stdlib.add_logger_name,
            structlog.stdlib.add_log_level,
            structlog.stdlib.PositionalArgumentsFormatter(),
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            structlog.processors.UnicodeDecoder(),
            structlog.processors.JSONRenderer()
        ],
        context_class=dict,
        logger_factory=structlog.stdlib.LoggerFactory(),
        cache_logger_on_first_use=True,
    )
    
    # Configure root logger
    root_logger = logging.getLogger()
    root_logger.setLevel(getattr(logging, log_level))
    
    # JSON formatter for console output
    json_handler = logging.StreamHandler(sys.stdout)
    json_handler.setFormatter(JsonFormatter(service_name=service_name))
    
    # Remove existing handlers
    for handler in root_logger.handlers[:]:
        root_logger.removeHandler(handler)
    
    root_logger.addHandler(json_handler)


class JsonFormatter(jsonlogger.JsonFormatter):
    """Custom JSON formatter with service metadata."""
    
    def __init__(self, service_name: str = "faq-service", *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.service_name = service_name
        self.hostname = os.getenv("HOSTNAME", "unknown")
        self.environment = os.getenv("ENVIRONMENT", "development")
    
    def add_fields(self, log_record: Dict[str, Any], record: logging.LogRecord, message_dict: Dict[str, Any]) -> None:
        """Add custom fields to JSON log."""
        super().add_fields(log_record, record, message_dict)
        
        # Add service metadata
        log_record["service"] = self.service_name
        log_record["hostname"] = self.hostname
        log_record["environment"] = self.environment
        log_record["timestamp"] = datetime.now(timezone.utc).isoformat()
        
        # Add severity levels
        if record.levelno >= logging.ERROR:
            log_record["severity"] = "ERROR"
        elif record.levelno >= logging.WARNING:
            log_record["severity"] = "WARNING"
        elif record.levelno >= logging.INFO:
            log_record["severity"] = "INFO"
        else:
            log_record["severity"] = "DEBUG"
        
        # Add trace ID if available
        if hasattr(record, "trace_id"):
            log_record["trace_id"] = record.trace_id
        
        # Add tenant ID if available
        if hasattr(record, "tenant_id"):
            log_record["tenant_id"] = record.tenant_id


def get_logger(name: str) -> structlog.PrintLogger:
    """Get a configured logger instance."""
    return structlog.get_logger(name)
