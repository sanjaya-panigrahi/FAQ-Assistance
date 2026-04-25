package com.mytechstore.unified.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class GraphRebuildWorker {

    private static final Logger log = LoggerFactory.getLogger(GraphRebuildWorker.class);

    private final TaskService taskService;
    private final GraphPipelineService graphPipelineService;

    public GraphRebuildWorker(TaskService taskService, GraphPipelineService graphPipelineService) {
        this.taskService = taskService;
        this.graphPipelineService = graphPipelineService;
    }

    @Async("graphTaskExecutor")
    public void rebuildIndex(String taskId) {
        try {
            taskService.markRunning(taskId);
            graphPipelineService.rebuildIndex();
            taskService.markComplete(taskId);
        } catch (Exception ex) {
            taskService.markFailed(taskId, ex.getMessage());
            log.error("Graph rebuild failed for task {}", taskId, ex);
        }
    }
}
