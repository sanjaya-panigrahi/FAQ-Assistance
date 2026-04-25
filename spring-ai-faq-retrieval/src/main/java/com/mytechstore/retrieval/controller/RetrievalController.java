package com.mytechstore.retrieval.controller;

import com.mytechstore.retrieval.dto.RetrievalQueryRequest;
import com.mytechstore.retrieval.dto.RetrievalQueryResponse;
import com.mytechstore.retrieval.service.RetrievalPipelineService;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class RetrievalController {

    private static final Logger logger = LoggerFactory.getLogger(RetrievalController.class);

    private final RetrievalPipelineService retrievalPipelineService;

    public RetrievalController(RetrievalPipelineService retrievalPipelineService) {
        this.retrievalPipelineService = retrievalPipelineService;
    }

    @GetMapping("/actuator/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(retrievalPipelineService.health());
    }

    // V1 API - Original implementation
    @PostMapping("/v1/retrieval/query")
    public ResponseEntity<RetrievalQueryResponse> queryV1(
            @Valid @RequestBody RetrievalQueryRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        logger.info("API v1 query request - idempotency_key: {}", idempotencyKey);
        return ResponseEntity.ok(retrievalPipelineService.query(request));
    }

    // V2 API - Enhanced with additional metadata
    @PostMapping("/v2/retrieval/query")
    public ResponseEntity<RetrievalQueryResponse> queryV2(
            @Valid @RequestBody RetrievalQueryRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        logger.info("API v2 query request - idempotency_key: {}, request_id: {}", idempotencyKey, requestId);
        
        // V2 Enhancement: Add request tracing context
        if (requestId != null) {
            logger.debug("Request tracing enabled with ID: {}", requestId);
        }
        
        RetrievalQueryResponse response = retrievalPipelineService.query(request);
        return ResponseEntity.ok(response);
    }

    // Legacy endpoint - redirects to v1
    @PostMapping("/retrieval/query")
    public ResponseEntity<RetrievalQueryResponse> query(
            @Valid @RequestBody RetrievalQueryRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        logger.info("Legacy API query request (redirecting to v1) - idempotency_key: {}", idempotencyKey);
        return queryV1(request, idempotencyKey);
    }

    @PostMapping(value = "/retrieval/query-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> queryStream(@Valid @RequestBody RetrievalQueryRequest request) {
        return retrievalPipelineService.queryStream(request.question(), request.tenantId());
    }
}
