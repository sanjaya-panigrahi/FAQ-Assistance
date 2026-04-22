package com.mytechstore.graphrag.event;

public final class GraphTaskEventTypes {

    public static final String TASK_CREATED = "TASK_CREATED";
    public static final String TASK_RUNNING = "TASK_RUNNING";
    public static final String TASK_COMPLETED = "TASK_COMPLETED";
    public static final String TASK_FAILED = "TASK_FAILED";

    private GraphTaskEventTypes() {
        // Utility class
    }
}
