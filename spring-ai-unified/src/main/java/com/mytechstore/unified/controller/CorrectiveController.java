package com.mytechstore.unified.controller;

import java.util.Map;

import com.mytechstore.unified.dto.RagRequest;
import com.mytechstore.unified.dto.CorrectiveRagResponse;
import com.mytechstore.unified.service.GuardrailPipelineService;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import reactor.core.publisher.Flux;

@Validated
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/corrective/api")
public class CorrectiveController {

    private final GuardrailPipelineService pipelineService;

    public CorrectiveController(GuardrailPipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PostMapping("/index/rebuild")
    public ResponseEntity<Map<String, String>> rebuildIndex() {
        return ResponseEntity.ok(Map.of("status", pipelineService.rebuildIndex()));
    }

    @PostMapping("/query/ask")
    public ResponseEntity<CorrectiveRagResponse> ask(@Valid @RequestBody RagRequest request) {
        return ResponseEntity.ok(pipelineService.ask(request.question(), request.customerId()));
    }

    @PostMapping(value = "/query/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> askStream(@Valid @RequestBody RagRequest request) {
        return pipelineService.askStream(request.question(), request.customerId());
    }
}
