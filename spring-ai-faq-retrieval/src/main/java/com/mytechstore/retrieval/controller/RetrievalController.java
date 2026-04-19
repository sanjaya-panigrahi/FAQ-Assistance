package com.mytechstore.retrieval.controller;

import com.mytechstore.retrieval.dto.RetrievalQueryRequest;
import com.mytechstore.retrieval.dto.RetrievalQueryResponse;
import com.mytechstore.retrieval.service.RetrievalPipelineService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class RetrievalController {

    private final RetrievalPipelineService retrievalPipelineService;

    public RetrievalController(RetrievalPipelineService retrievalPipelineService) {
        this.retrievalPipelineService = retrievalPipelineService;
    }

    @GetMapping("/actuator/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(retrievalPipelineService.health());
    }

    @PostMapping("/retrieval/query")
    public ResponseEntity<RetrievalQueryResponse> query(@Valid @RequestBody RetrievalQueryRequest request) {
        return ResponseEntity.ok(retrievalPipelineService.query(request));
    }
}
