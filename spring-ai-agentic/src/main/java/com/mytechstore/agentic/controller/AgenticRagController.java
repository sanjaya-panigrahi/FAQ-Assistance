package com.mytechstore.agentic.controller;

import java.util.Map;

import com.mytechstore.agentic.dto.RagRequest;
import com.mytechstore.agentic.dto.RagResponse;
import com.mytechstore.agentic.service.AgenticPipelineService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class AgenticRagController {

    private final AgenticPipelineService pipelineService;

    public AgenticRagController(AgenticPipelineService pipelineService) {
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
