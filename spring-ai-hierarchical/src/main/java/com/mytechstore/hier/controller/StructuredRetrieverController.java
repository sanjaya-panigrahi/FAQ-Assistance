package com.mytechstore.hier.controller;

import java.util.Map;

import com.mytechstore.hier.dto.RagRequest;
import com.mytechstore.hier.dto.RagResponse;
import com.mytechstore.hier.service.StructuredPipelineService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class StructuredRetrieverController {

    private final StructuredPipelineService pipelineService;

    public StructuredRetrieverController(StructuredPipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PostMapping("/index/rebuild")
    public ResponseEntity<Map<String, String>> rebuildIndex() {
        return ResponseEntity.ok(Map.of("status", pipelineService.rebuildIndex()));
    }

    @PostMapping("/query/ask")
    public ResponseEntity<RagResponse> ask(@RequestBody RagRequest request) {
        return ResponseEntity.ok(pipelineService.ask(request.question()));
    }
}