package com.mytechstore.graphrag.controller;

import java.util.Map;

import com.mytechstore.graphrag.dto.RagRequest;
import com.mytechstore.graphrag.dto.RagResponse;
import com.mytechstore.graphrag.service.GraphPipelineService;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@Validated
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class GraphRagController {

    private final GraphPipelineService pipelineService;

    public GraphRagController(GraphPipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PostMapping("/index/rebuild")
    public ResponseEntity<Map<String, String>> rebuildIndex() {
        return ResponseEntity.ok(Map.of("status", pipelineService.rebuildIndex()));
    }

    @PostMapping("/query/ask")
    public ResponseEntity<RagResponse> ask(@Valid @RequestBody RagRequest request) {
        return ResponseEntity.ok(pipelineService.ask(request.question(), request.customerId()));
    }
}