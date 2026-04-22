package com.mytechstore.graphrag.controller;

import java.util.Map;
import java.util.List;

import com.mytechstore.graphrag.dto.RagRequest;
import com.mytechstore.graphrag.dto.RagResponse;
import com.mytechstore.graphrag.dto.TaskResponse;
import com.mytechstore.graphrag.event.GraphTaskEventStats;
import com.mytechstore.graphrag.service.GraphRebuildWorker;
import com.mytechstore.graphrag.service.GraphPipelineService;
import com.mytechstore.graphrag.service.TaskService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final TaskService taskService;
    private final GraphRebuildWorker graphRebuildWorker;
    private final GraphTaskEventStats eventStats;

    public GraphRagController(
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
    public ResponseEntity<RagResponse> ask(@Valid @RequestBody RagRequest request) {
        return ResponseEntity.ok(pipelineService.ask(request.question(), request.customerId()));
    }
}