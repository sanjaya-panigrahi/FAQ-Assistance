package com.mytechstore.unified.controller;

import java.util.List;
import java.util.Map;

import com.mytechstore.unified.dto.RagRequest;
import com.mytechstore.unified.dto.GraphRagResponse;
import com.mytechstore.unified.dto.TaskResponse;
import com.mytechstore.unified.event.GraphTaskEventStats;
import com.mytechstore.unified.service.GraphRebuildWorker;
import com.mytechstore.unified.service.GraphPipelineService;
import com.mytechstore.unified.service.TaskService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import reactor.core.publisher.Flux;

@Validated
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/graph/api")
public class GraphController {

    private final GraphPipelineService pipelineService;
    private final TaskService taskService;
    private final GraphRebuildWorker graphRebuildWorker;
    private final GraphTaskEventStats eventStats;

    public GraphController(
        GraphPipelineService pipelineService,
        TaskService taskService,
        GraphRebuildWorker graphRebuildWorker,
        GraphTaskEventStats eventStats
    ) {
        this.pipelineService = pipelineService;
        this.taskService = taskService;
        this.graphRebuildWorker = graphRebuildWorker;
        this.eventStats = eventStats;
    }

    @PostMapping("/index/rebuild")
    public ResponseEntity<TaskResponse> rebuildIndex() {
        TaskResponse task = taskService.createTask("REBUILD_GRAPH_INDEX");
        graphRebuildWorker.rebuildIndex(task.taskId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(task);
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<TaskResponse> getTaskStatus(@PathVariable String taskId) {
        return ResponseEntity.ok(taskService.getTask(taskId));
    }

    @GetMapping("/tasks")
    public ResponseEntity<List<TaskResponse>> listTasks(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(taskService.listTasks(limit));
    }

    @GetMapping("/events/stats")
    public ResponseEntity<Map<String, Object>> getEventStats() {
        return ResponseEntity.ok(eventStats.snapshot());
    }

    @PostMapping("/query/ask")
    public ResponseEntity<GraphRagResponse> ask(@Valid @RequestBody RagRequest request) {
        return ResponseEntity.ok(pipelineService.ask(request.question(), request.customerId()));
    }

    @PostMapping(value = "/query/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> askStream(@Valid @RequestBody RagRequest request) {
        return pipelineService.askStream(request.question(), request.customerId());
    }
}
