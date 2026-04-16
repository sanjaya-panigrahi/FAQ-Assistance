package com.mytechstore.vision.controller;

import java.util.Map;

import com.mytechstore.vision.dto.VisionRagRequest;
import com.mytechstore.vision.dto.VisionRagResponse;
import com.mytechstore.vision.service.VisionPipelineService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class VisionRagController {

    private final VisionPipelineService pipelineService;

    public VisionRagController(VisionPipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PostMapping("/index/rebuild")
    public ResponseEntity<Map<String, String>> rebuildIndex() {
        return ResponseEntity.ok(Map.of("status", pipelineService.rebuildIndex()));
    }

    @PostMapping("/query/ask")
    public ResponseEntity<VisionRagResponse> ask(@RequestBody VisionRagRequest request) {
        return ResponseEntity.ok(pipelineService.ask(request.question(), request.imageDescription()));
    }
}