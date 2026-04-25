import { useEffect, useMemo, useRef, useState } from "react";

const apiMode = import.meta.env.VITE_API_MODE || "kong";
const kongGatewayUrl = import.meta.env.VITE_API_BASE_URL || "http://localhost:9080";
const isDirectApiMode = apiMode === "direct";
const graphWarmupTimeoutMs = Number(import.meta.env.VITE_GRAPH_WARMUP_TIMEOUT_MS || 120000);

function resolveApiUrl(envKey, directDefaultPath, kongPath) {
  const envValue = import.meta.env[envKey];
  if (envValue) {
    return envValue;
  }
  if (isDirectApiMode) {
    return directDefaultPath;
  }
  return `${kongGatewayUrl}${kongPath}`;
}

const ingestionApiUrl = resolveApiUrl(
  "VITE_INGESTION_API_URL",
  "http://localhost:9000/api/faq-ingestion",
  "/spring/ingestion/api/faq-ingestion"
);
const analyticsApiUrl = resolveApiUrl(
  "VITE_ANALYTICS_API_URL",
  "http://localhost:9191/api/analytics",
  "/spring/analytics/api/analytics"
);
const analyticsPresetsStorageKey = "faq-assistance.analytics.filter-presets";
const documentPresetsStorageKey = "faq-assistance.documents.filter-presets";
const fallbackCustomers = [
  { customerId: "mytechstore", name: "mytechstore" },
];

const services = [
  { id: "agentic",    label: "Agentic RAG" },
  { id: "retrieval",  label: "RAG Retrieval" },
  { id: "graph",      label: "Graph RAG" },
  // { id: "corrective", label: "Corrective RAG" },
  // { id: "multimodal", label: "Multimodal RAG" },
  // { id: "hierarchical", label: "Hierarchical RAG" },
];
const enabledServiceIds = new Set(["agentic", "retrieval", "graph"]);

// Base URLs keyed by [framework][serviceId]
const serviceUrls = {
  "spring-ai": {
    agentic: resolveApiUrl("VITE_SPRING_AGENTIC_API_URL", "http://localhost:9000/agentic/api", "/spring/agentic/api"),
    retrieval: resolveApiUrl("VITE_SPRING_RETRIEVAL_API_URL", "http://localhost:9010/api", "/spring/retrieval/api"),
    graph: resolveApiUrl("VITE_SPRING_GRAPH_API_URL", "http://localhost:9000/graph/api", "/spring/graph/api"),
    corrective: resolveApiUrl("VITE_SPRING_CORRECTIVE_API_URL", "http://localhost:9000/corrective/api", "/spring/corrective/api"),
    multimodal: resolveApiUrl("VITE_SPRING_MULTIMODAL_API_URL", "http://localhost:9000/multimodal/api", "/spring/multimodal/api"),
    hierarchical: resolveApiUrl("VITE_SPRING_HIERARCHICAL_API_URL", "http://localhost:9000/hierarchical/api", "/spring/hierarchical/api"),
  },
  langchain: {
    agentic: resolveApiUrl("VITE_LANGCHAIN_AGENTIC_API_URL", "http://localhost:8180/agentic/api", "/langchain/agentic/api"),
    retrieval: resolveApiUrl("VITE_LANGCHAIN_RETRIEVAL_API_URL", "http://localhost:8180/retrieval/api", "/langchain/retrieval/api"),
    graph: resolveApiUrl("VITE_LANGCHAIN_GRAPH_API_URL", "http://localhost:8180/graph/api", "/langchain/graph/api"),
    corrective: resolveApiUrl("VITE_LANGCHAIN_CORRECTIVE_API_URL", "http://localhost:8180/corrective/api", "/langchain/corrective/api"),
    multimodal: resolveApiUrl("VITE_LANGCHAIN_MULTIMODAL_API_URL", "http://localhost:8180/multimodal/api", "/langchain/multimodal/api"),
    hierarchical: resolveApiUrl("VITE_LANGCHAIN_HIERARCHICAL_API_URL", "http://localhost:8180/hierarchical/api", "/langchain/hierarchical/api"),
  },
  langgraph: {
    agentic: resolveApiUrl("VITE_LANGGRAPH_AGENTIC_API_URL", "http://localhost:8280/agentic/api", "/langgraph/agentic/api"),
    retrieval: resolveApiUrl("VITE_LANGGRAPH_RETRIEVAL_API_URL", "http://localhost:8280/retrieval/api", "/langgraph/retrieval/api"),
    graph: resolveApiUrl("VITE_LANGGRAPH_GRAPH_API_URL", "http://localhost:8280/graph/api", "/langgraph/graph/api"),
    corrective: resolveApiUrl("VITE_LANGGRAPH_CORRECTIVE_API_URL", "http://localhost:8280/corrective/api", "/langgraph/corrective/api"),
    multimodal: resolveApiUrl("VITE_LANGGRAPH_MULTIMODAL_API_URL", "http://localhost:8280/multimodal/api", "/langgraph/multimodal/api"),
    hierarchical: resolveApiUrl("VITE_LANGGRAPH_HIERARCHICAL_API_URL", "http://localhost:8280/hierarchical/api", "/langgraph/hierarchical/api"),
  },
};

function resolveUrl(frameworkValue, serviceId) {
  return serviceUrls[frameworkValue]?.[serviceId] ?? serviceUrls["spring-ai"][serviceId];
}

const frameworkOptions = [
  { value: "spring-ai", label: "Spring AI" },
  { value: "langchain", label: "LangChain" },
  { value: "langgraph", label: "LangGraph" },
];

function App() {
  const [mode, setMode] = useState("compare");
  const [selectedServiceId, setSelectedServiceId] = useState("agentic");
  const [framework, setFramework] = useState("spring-ai");
  const [customer, setCustomer] = useState("");
  const [customers, setCustomers] = useState(fallbackCustomers);
  const [question, setQuestion] = useState("What is your laptop return policy?");
  const [imageDescription, setImageDescription] = useState("");
  const [uploadedImage, setUploadedImage] = useState(null);
  const [transcript, setTranscript] = useState([]);
  const [loading, setLoading] = useState(false);
  const [uploadingFaq, setUploadingFaq] = useState(false);
  const [customerBusy, setCustomerBusy] = useState(false);
  const [error, setError] = useState("");
  const [graphWarmupStatus, setGraphWarmupStatus] = useState("");
  const [graphTasks, setGraphTasks] = useState([]);
  const [graphTasksLoading, setGraphTasksLoading] = useState(false);
  const [graphTasksError, setGraphTasksError] = useState("");
  const [graphTaskFilter, setGraphTaskFilter] = useState("active");
  const [expandedGraphTaskIds, setExpandedGraphTaskIds] = useState(() => new Set());
  const [uploadedFaqName, setUploadedFaqName] = useState("");
  const [selectedFaqFiles, setSelectedFaqFiles] = useState([]);
  const [documentManagerOpen, setDocumentManagerOpen] = useState(false);
  const [faqDragActive, setFaqDragActive] = useState(false);
  const [customerStatus, setCustomerStatus] = useState("Loading customers from the ingestion service...");
  const [faqUploadStatus, setFaqUploadStatus] = useState("");
  const [faqUploadResult, setFaqUploadResult] = useState(null);
  const [customerDocuments, setCustomerDocuments] = useState([]);
  const [documentsLoading, setDocumentsLoading] = useState(false);
  const [documentsStatus, setDocumentsStatus] = useState("");
  const [documentsCustomerFilter, setDocumentsCustomerFilter] = useState("all");
  const [documentsTypeFilter, setDocumentsTypeFilter] = useState("all");
  const [documentsStatusFilter, setDocumentsStatusFilter] = useState("all");
  const [documentsSearchTerm, setDocumentsSearchTerm] = useState("");
  const [documentPresets, setDocumentPresets] = useState([]);
  const [newDocumentPresetName, setNewDocumentPresetName] = useState("");
  const [newCustomerId, setNewCustomerId] = useState("");
  const [newCustomerName, setNewCustomerName] = useState("");
  const [newCustomerDescription, setNewCustomerDescription] = useState("");
  const [dashboardLoading, setDashboardLoading] = useState(false);
  const [dashboardError, setDashboardError] = useState("");
  const [dashboardRows, setDashboardRows] = useState([]);
  const [dashboardSummary, setDashboardSummary] = useState([]);
  const [recentRuns, setRecentRuns] = useState([]);
  const [analyticsVisible, setAnalyticsVisible] = useState(false);
  const [scoreDistribution, setScoreDistribution] = useState(null);
  const [analyticsCustomerFilter, setAnalyticsCustomerFilter] = useState("all");
  const [analyticsFrameworkFilter, setAnalyticsFrameworkFilter] = useState("all");
  const [analyticsPatternFilter, setAnalyticsPatternFilter] = useState("all");
  const [analyticsSearchTerm, setAnalyticsSearchTerm] = useState("");
  const [analyticsPresets, setAnalyticsPresets] = useState([]);
  const [newAnalyticsPresetName, setNewAnalyticsPresetName] = useState("");
  const fileInputRef = useRef(null);
  const imageInputRef = useRef(null);
  const presetImportInputRef = useRef(null);
  const documentPresetImportInputRef = useRef(null);
  const graphRebuildCacheRef = useRef(new Set());
  const graphRebuildPromiseRef = useRef(new Map());
  const graphCustomerDataCacheRef = useRef(new Set());
  const graphWarmupPendingCountRef = useRef(0);

  const selectedService = useMemo(
    () => services.find((service) => service.id === selectedServiceId) ?? services[0],
    [selectedServiceId]
  );
  useEffect(() => {
    if (!enabledServiceIds.has(selectedServiceId)) {
      setSelectedServiceId("agentic");
    }
  }, [selectedServiceId]);

  const activeServices = useMemo(() => {
    return [selectedService];
  }, [selectedService]);

  // In compare mode each framework gets its own real backend URL for the selected pattern.
  const compareFrameworks = useMemo(
    () =>
      frameworkOptions.map((option) => ({
        ...option,
        ragPattern: selectedService.label,
        url: resolveUrl(option.value, selectedService.id),
      })),
    [selectedService]
  );

  const customerIdsForAnalytics = useMemo(() => {
    const ids = customers
      .map((entry) => entry.customerId)
      .filter((entry) => typeof entry === "string" && entry.trim().length > 0)
      .map((entry) => entry.trim());

    if (ids.length === 0 && customer) {
      return [customer.trim()];
    }

    return Array.from(new Set(ids));
  }, [customers, customer]);

  const analyticsCustomers = useMemo(
    () => Array.from(new Set(dashboardRows.map((row) => row.customer))).sort(),
    [dashboardRows]
  );

  const analyticsFrameworks = useMemo(
    () => Array.from(new Set(dashboardRows.map((row) => row.framework))).sort(),
    [dashboardRows]
  );

  const analyticsPatterns = useMemo(
    () => Array.from(new Set(dashboardRows.map((row) => row.ragPattern))).sort(),
    [dashboardRows]
  );

  const normalizedAnalyticsSearchTerm = analyticsSearchTerm.trim().toLowerCase();

  const filteredDashboardRows = useMemo(() => {
    return dashboardRows.filter((row) => {
      if (analyticsCustomerFilter !== "all" && row.customer !== analyticsCustomerFilter) {
        return false;
      }
      if (analyticsFrameworkFilter !== "all" && row.framework !== analyticsFrameworkFilter) {
        return false;
      }
      if (analyticsPatternFilter !== "all" && row.ragPattern !== analyticsPatternFilter) {
        return false;
      }

      if (!normalizedAnalyticsSearchTerm) {
        return true;
      }

      const haystack = `${row.ragPattern} ${row.customer} ${row.framework}`.toLowerCase();
      return haystack.includes(normalizedAnalyticsSearchTerm);
    });
  }, [
    dashboardRows,
    analyticsCustomerFilter,
    analyticsFrameworkFilter,
    analyticsPatternFilter,
    normalizedAnalyticsSearchTerm,
  ]);

  const visibleGraphTasks = useMemo(() => {
    const isActiveStatus = (statusValue) => {
      const status = String(statusValue || "").toUpperCase();
      return status === "PENDING" || status === "RUNNING" || status === "STARTED" || status === "RETRY";
    };

    return graphTasks.filter((task) => {
      if (graphTaskFilter === "all") {
        return true;
      }
      return isActiveStatus(task.status);
    });
  }, [graphTasks, graphTaskFilter]);

  useEffect(() => {
    setExpandedGraphTaskIds((currentValue) => {
      const validTaskIds = new Set(
        graphTasks
          .map((task) => String(task?.taskId || "").trim())
          .filter((taskId) => taskId.length > 0)
      );

      const nextValue = new Set();
      currentValue.forEach((taskId) => {
        if (validTaskIds.has(taskId)) {
          nextValue.add(taskId);
        }
      });
      return nextValue;
    });
  }, [graphTasks]);

  const filteredDashboardSummary = useMemo(() => {
    const aggregateByFramework = new Map();

    filteredDashboardRows.forEach((row) => {
      const totalRuns = Number(row.totalRuns ?? 0);
      const runWeight = Math.max(totalRuns, 1);
      const avgLatencyMs = Number(row.avgLatencyMs ?? 0);
      const successRate = Number(row.successRate ?? 0);
      const avgEffectiveRagScore = Number(row.avgEffectiveRagScore ?? 0);

      const currentAggregate = aggregateByFramework.get(row.framework) || {
        framework: row.framework,
        runWeight: 0,
        weightedSuccess: 0,
        weightedLatency: 0,
        weightedEffectiveRag: 0,
      };

      currentAggregate.runWeight += runWeight;
      currentAggregate.weightedSuccess += successRate * runWeight;
      currentAggregate.weightedLatency += avgLatencyMs * runWeight;
      currentAggregate.weightedEffectiveRag += avgEffectiveRagScore * runWeight;
      aggregateByFramework.set(row.framework, currentAggregate);
    });

    return Array.from(aggregateByFramework.values())
      .map((entry) => ({
        framework: entry.framework,
        totalRuns: entry.runWeight,
        successRate: entry.runWeight ? entry.weightedSuccess / entry.runWeight : 0,
        avgLatencyMs: entry.runWeight ? entry.weightedLatency / entry.runWeight : 0,
        avgEffectiveRagScore: entry.runWeight ? entry.weightedEffectiveRag / entry.runWeight : 0,
      }))
      .sort((leftRow, rightRow) => rightRow.avgEffectiveRagScore - leftRow.avgEffectiveRagScore);
  }, [filteredDashboardRows]);

  const filteredRecentRuns = useMemo(() => {
    return recentRuns.filter((row) => {
      if (analyticsCustomerFilter !== "all" && row.customer !== analyticsCustomerFilter) {
        return false;
      }
      if (analyticsFrameworkFilter !== "all" && row.framework !== analyticsFrameworkFilter) {
        return false;
      }
      if (analyticsPatternFilter !== "all" && row.ragPattern !== analyticsPatternFilter) {
        return false;
      }

      if (!normalizedAnalyticsSearchTerm) {
        return true;
      }

      const haystack = `${row.ragPattern} ${row.customer} ${row.framework} ${row.status}`.toLowerCase();
      return haystack.includes(normalizedAnalyticsSearchTerm);
    });
  }, [
    recentRuns,
    analyticsCustomerFilter,
    analyticsFrameworkFilter,
    analyticsPatternFilter,
    normalizedAnalyticsSearchTerm,
  ]);

  const analyticsFilterSummary = useMemo(() => {
    const frameworks = new Set(filteredDashboardRows.map((row) => row.framework));
    const patterns = new Set(filteredDashboardRows.map((row) => row.ragPattern));
    const customersSet = new Set(filteredDashboardRows.map((row) => row.customer));

    return {
      rowCount: filteredDashboardRows.length,
      frameworkCount: frameworks.size,
      patternCount: patterns.size,
      customerCount: customersSet.size,
    };
  }, [filteredDashboardRows]);

  const quickFilterChips = useMemo(
    () => [
      { id: "all", label: "All", framework: "all", pattern: "all" },
      { id: "spring-ai", label: "Spring AI", framework: "Spring AI", pattern: "all" },
      { id: "langchain", label: "LangChain", framework: "LangChain", pattern: "all" },
      { id: "langgraph", label: "LangGraph", framework: "LangGraph", pattern: "all" },
      { id: "agentic-rag", label: "Agentic RAG", framework: "all", pattern: "Agentic RAG" },
      { id: "rag-retrieval", label: "RAG Retrieval", framework: "all", pattern: "RAG Retrieval" },
      { id: "graph-rag", label: "Graph RAG", framework: "all", pattern: "Graph RAG" },
      // { id: "corrective-rag", label: "Corrective RAG", framework: "all", pattern: "Corrective RAG" },
      // { id: "multimodal-rag", label: "Multimodal RAG", framework: "all", pattern: "Multimodal RAG" },
      // { id: "hierarchical-rag", label: "Hierarchical RAG", framework: "all", pattern: "Hierarchical RAG" },
    ],
    []
  );

  const activeQuickChipId = useMemo(() => {
    const match = quickFilterChips.find(
      (chip) => chip.framework === analyticsFrameworkFilter && chip.pattern === analyticsPatternFilter
    );
    return match?.id || null;
  }, [quickFilterChips, analyticsFrameworkFilter, analyticsPatternFilter]);

  const currentAnalyticsFilterState = useMemo(
    () => ({
      customer: analyticsCustomerFilter,
      framework: analyticsFrameworkFilter,
      pattern: analyticsPatternFilter,
      search: analyticsSearchTerm,
    }),
    [analyticsCustomerFilter, analyticsFrameworkFilter, analyticsPatternFilter, analyticsSearchTerm]
  );

  const customerIdsAnalyticsKey = useMemo(
    () => customerIdsForAnalytics.join("|"),
    [customerIdsForAnalytics]
  );

  const bestAverageFramework = useMemo(() => {
    if (filteredDashboardSummary.length === 0) {
      return null;
    }

    return filteredDashboardSummary.reduce((bestRow, currentRow) => {
      if (!bestRow) {
        return currentRow;
      }

      if (currentRow.avgEffectiveRagScore > bestRow.avgEffectiveRagScore) {
        return currentRow;
      }

      return bestRow;
    }, null);
  }, [filteredDashboardSummary]);

  const preferredTechBySegment = useMemo(() => {
    const preferred = new Set();
    const grouped = new Map();

    filteredDashboardRows.forEach((row) => {
      const key = `${row.ragPattern}||${row.customer}`;
      if (!grouped.has(key)) {
        grouped.set(key, []);
      }
      grouped.get(key).push(row);
    });

    grouped.forEach((rows, key) => {
      const bestScore = rows.reduce(
        (best, row) => Math.max(best, Number(row.avgEffectiveRagScore ?? 0)),
        Number.NEGATIVE_INFINITY
      );
      rows.forEach((row) => {
        if (Number(row.avgEffectiveRagScore ?? 0) === bestScore) {
          preferred.add(`${key}||${row.framework}`);
        }
      });
    });

    return preferred;
  }, [filteredDashboardRows]);

  const needsImageContext = selectedServiceId === "multimodal";
  const trimmedQuestion = question.trim();
  const trimmedCustomerId = customer.trim();
  const trimmedNewCustomerId = newCustomerId.trim();
  const trimmedNewCustomerName = newCustomerName.trim();
  const hasQuestion = trimmedQuestion.length > 0;
  const hasTranscript = transcript.length > 0;
  const searchEnabled = true;
  const hasCustomer = trimmedCustomerId.length > 0;
  const canAsk = searchEnabled && !loading && !uploadingFaq && hasQuestion && hasCustomer;
  const canExport = !loading && hasTranscript;
  const canStartNewConversation = !loading && (hasTranscript || Boolean(error) || Boolean(uploadedFaqName));
  const canUploadFaq = !loading && !uploadingFaq && selectedFaqFiles.length > 0;
  const canCreateCustomer = !customerBusy && trimmedNewCustomerId.length > 0 && trimmedNewCustomerName.length > 0;
  const primaryActionLabel = loading
    ? mode === "compare"
      ? "Running compare..."
      : "Running query..."
    : mode === "compare"
      ? "Compare all backends"
      : "Run selected backend";

  const documentCustomerOptions = useMemo(
    () => Array.from(new Set(customerDocuments.map((entry) => entry.customerCode))).sort(),
    [customerDocuments]
  );

  const documentTypeOptions = useMemo(
    () => Array.from(new Set(customerDocuments.map((entry) => String(entry.fileType || "").toUpperCase()).filter(Boolean))).sort(),
    [customerDocuments]
  );

  const documentStatusOptions = useMemo(
    () => Array.from(new Set(customerDocuments.map((entry) => entry.processingStatus).filter(Boolean))).sort(),
    [customerDocuments]
  );

  const normalizedDocumentsSearchTerm = documentsSearchTerm.trim().toLowerCase();

  const filteredCustomerDocuments = useMemo(() => {
    return customerDocuments.filter((entry) => {
      if (documentsCustomerFilter !== "all" && entry.customerCode !== documentsCustomerFilter) {
        return false;
      }
      const entryFileType = String(entry.fileType || "").toUpperCase();
      if (documentsTypeFilter !== "all" && entryFileType !== documentsTypeFilter) {
        return false;
      }
      if (documentsStatusFilter !== "all" && entry.processingStatus !== documentsStatusFilter) {
        return false;
      }
      if (!normalizedDocumentsSearchTerm) {
        return true;
      }
      const haystack = `${entry.customerCode} ${entry.originalFileName} ${entry.processingStatus} ${entry.detectedStructure || ""}`.toLowerCase();
      return haystack.includes(normalizedDocumentsSearchTerm);
    });
  }, [
    customerDocuments,
    documentsCustomerFilter,
    documentsTypeFilter,
    documentsStatusFilter,
    normalizedDocumentsSearchTerm,
  ]);

  const documentFilterSummary = useMemo(() => {
    const customersSet = new Set(filteredCustomerDocuments.map((entry) => entry.customerCode));
    const statusSet = new Set(filteredCustomerDocuments.map((entry) => entry.processingStatus));
    const typeSet = new Set(filteredCustomerDocuments.map((entry) => String(entry.fileType || "").toUpperCase()));

    return {
      rowCount: filteredCustomerDocuments.length,
      customerCount: customersSet.size,
      statusCount: statusSet.size,
      typeCount: typeSet.size,
    };
  }, [filteredCustomerDocuments]);

  const documentQuickFilterChips = useMemo(
    () => [
      { id: "all", label: "All", status: "all", type: "all" },
      { id: "completed", label: "Completed", status: "COMPLETED", type: "all" },
      { id: "processing", label: "Processing", status: "PROCESSING", type: "all" },
      { id: "failed", label: "Failed", status: "FAILED", type: "all" },
      { id: "markdown", label: "Markdown", status: "all", type: "MD" },
      { id: "pdf", label: "PDF", status: "all", type: "PDF" },
    ],
    []
  );

  const activeDocumentQuickChipId = useMemo(() => {
    const match = documentQuickFilterChips.find(
      (chip) => chip.status === documentsStatusFilter && chip.type === documentsTypeFilter
    );
    return match?.id || null;
  }, [documentQuickFilterChips, documentsStatusFilter, documentsTypeFilter]);

  const currentDocumentFilterState = useMemo(
    () => ({
      customer: documentsCustomerFilter,
      status: documentsStatusFilter,
      type: documentsTypeFilter,
      search: documentsSearchTerm,
    }),
    [documentsCustomerFilter, documentsStatusFilter, documentsTypeFilter, documentsSearchTerm]
  );

  async function parseResponse(response) {
    const contentType = response.headers.get("content-type") || "";

    if (contentType.includes("application/json")) {
      return response.json();
    }

    const text = await response.text();
    return text ? { message: text } : {};
  }

  async function loadGraphTasks(limit = 10, { silent = false } = {}) {
    if (!silent) {
      setGraphTasksLoading(true);
    }

    try {
      const graphBaseUrl = resolveUrl(framework, "graph");
      const response = await fetch(`${graphBaseUrl}/tasks?limit=${encodeURIComponent(limit)}`);
      const data = await parseResponse(response);

      if (!response.ok) {
        throw new Error(data?.detail || data?.message || `Unable to load graph tasks (${response.status}).`);
      }

      setGraphTasks(Array.isArray(data) ? data : []);
      setGraphTasksError("");
    } catch (requestError) {
      setGraphTasksError(requestError.message || "Unable to load graph tasks.");
      if (!silent) {
        setGraphTasks([]);
      }
    } finally {
      if (!silent) {
        setGraphTasksLoading(false);
      }
    }
  }

  function beginGraphWarmup(message) {
    graphWarmupPendingCountRef.current += 1;
    setGraphWarmupStatus(message);
  }

  function updateGraphWarmup(message) {
    setGraphWarmupStatus(message);
  }

  function endGraphWarmup() {
    graphWarmupPendingCountRef.current = Math.max(0, graphWarmupPendingCountRef.current - 1);
    if (graphWarmupPendingCountRef.current === 0) {
      setGraphWarmupStatus("");
    }
  }

  function isGraphTaskComplete(status) {
    return status === "COMPLETE" || status === "SUCCESS";
  }

  function isGraphTaskFailed(status) {
    return status === "FAILED" || status === "FAILURE";
  }

  function formatTaskTime(isoValue) {
    if (!isoValue) {
      return "-";
    }
    const parsed = new Date(isoValue);
    if (Number.isNaN(parsed.getTime())) {
      return "-";
    }
    return parsed.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  }

  function formatTaskDateTime(isoValue) {
    if (!isoValue) {
      return "-";
    }
    const parsed = new Date(isoValue);
    if (Number.isNaN(parsed.getTime())) {
      return "-";
    }
    return parsed.toLocaleString();
  }

  function formatTaskPayload(task) {
    const normalizedTask = {
      taskId: task?.taskId || null,
      taskType: task?.taskType || null,
      status: task?.status || null,
      createdAt: task?.createdAt || null,
      startedAt: task?.startedAt || null,
      completedAt: task?.completedAt || null,
      updatedAt: task?.updatedAt || null,
      error: task?.error || null,
      result: task?.result ?? null,
    };

    try {
      return JSON.stringify(normalizedTask, null, 2);
    } catch {
      return "{\n  \"error\": \"Unable to render task payload\"\n}";
    }
  }

  function toggleTaskDetails(taskId) {
    const normalizedTaskId = String(taskId || "").trim();
    if (!normalizedTaskId) {
      return;
    }

    setExpandedGraphTaskIds((currentValue) => {
      const nextValue = new Set(currentValue);
      if (nextValue.has(normalizedTaskId)) {
        nextValue.delete(normalizedTaskId);
      } else {
        nextValue.add(normalizedTaskId);
      }
      return nextValue;
    });
  }

  async function waitForGraphTask(graphBaseUrl, taskId, frameworkValue, customerId) {
    const startedAt = Date.now();

    while (Date.now() - startedAt < graphWarmupTimeoutMs) {
      const response = await fetch(`${graphBaseUrl}/tasks/${encodeURIComponent(taskId)}`);
      const data = await parseResponse(response);
      if (!response.ok) {
        throw new Error(
          data?.detail || data?.message || `Graph task polling failed for ${frameworkValue} (status ${response.status}).`
        );
      }

      const status = String(data?.status || "").toUpperCase();
      updateGraphWarmup(`Preparing Graph RAG index for ${customerId} on ${frameworkValue} (${status || "PENDING"})...`);

      if (isGraphTaskComplete(status)) {
        return data;
      }

      if (isGraphTaskFailed(status)) {
        throw new Error(data?.error || data?.detail || `Graph rebuild failed for ${frameworkValue}.`);
      }

      await new Promise((resolve) => window.setTimeout(resolve, 1500));
    }

    throw new Error(`Graph rebuild timed out for ${frameworkValue}.`);
  }

  async function ensureGraphCustomerHasData(customerId) {
    const normalizedCustomerId = customerId.trim();
    if (!normalizedCustomerId) {
      throw new Error("customerId is required for Graph RAG.");
    }

    if (graphCustomerDataCacheRef.current.has(normalizedCustomerId)) {
      return;
    }

    const response = await fetch(
      `${ingestionApiUrl}/customers/${encodeURIComponent(normalizedCustomerId)}/documents`
    );
    const data = await parseResponse(response);

    if (!response.ok) {
      throw new Error(data.message || `Could not verify customer documents (status ${response.status}).`);
    }

    const documents = Array.isArray(data) ? data : [];
    const hasIndexedData = documents.some((entry) => {
      const status = String(entry.processingStatus || "").toUpperCase();
      return status === "" || status === "COMPLETED" || status === "INDEXED" || status === "EMBEDDING";
    });

    if (!hasIndexedData) {
      throw new Error(
        `No ingested FAQ data found for customer ${normalizedCustomerId}. Upload FAQ data before using Graph RAG.`
      );
    }

    graphCustomerDataCacheRef.current.add(normalizedCustomerId);
  }

  async function ensureGraphIndexReady(frameworkValue, customerId) {
    const normalizedCustomerId = customerId.trim();
    await ensureGraphCustomerHasData(normalizedCustomerId);

    // Neo4j index is shared across all frameworks — only rebuild once per customer
    const cacheKey = `graph::${normalizedCustomerId}`;
    if (graphRebuildCacheRef.current.has(cacheKey)) {
      return;
    }

    // If another framework is already rebuilding for this customer, wait for it
    const pendingPromise = graphRebuildPromiseRef.current.get(cacheKey);
    if (pendingPromise) {
      return pendingPromise;
    }

    const rebuildPromise = _doGraphRebuild(frameworkValue, normalizedCustomerId, cacheKey);
    graphRebuildPromiseRef.current.set(cacheKey, rebuildPromise);
    try {
      await rebuildPromise;
    } finally {
      graphRebuildPromiseRef.current.delete(cacheKey);
    }
  }

  async function _doGraphRebuild(frameworkValue, normalizedCustomerId, cacheKey) {
    const graphBaseUrl = resolveUrl(frameworkValue, "graph");
    const abortController = new AbortController();
    const timeoutId = window.setTimeout(() => abortController.abort(), graphWarmupTimeoutMs);
    beginGraphWarmup(`Preparing Graph RAG index for ${normalizedCustomerId} on ${frameworkValue}...`);

    try {
      const response = await fetch(`${graphBaseUrl}/index/rebuild`, {
        method: "POST",
        signal: abortController.signal,
      });
      const data = await parseResponse(response);

      if (response.ok && data?.taskId) {
        await loadGraphTasks(10, { silent: true });
        await waitForGraphTask(graphBaseUrl, data.taskId, frameworkValue, normalizedCustomerId);
        await loadGraphTasks(10, { silent: true });
        graphRebuildCacheRef.current.add(cacheKey);
        return;
      }

      if (response.ok) {
        graphRebuildCacheRef.current.add(cacheKey);
        return;
      }

      const message = String(
        data?.detail || data?.message || `Graph index rebuild failed for ${frameworkValue} (status ${response.status}).`
      );

      // Some graph services can return equivalent-index errors even when index is usable.
      if (
        message.toLowerCase().includes("equivalentschemarulealreadyexists") ||
        message.toLowerCase().includes("equivalent index already exists")
      ) {
        graphRebuildCacheRef.current.add(cacheKey);
        return;
      }

      console.warn(`Skipping graph warmup for ${frameworkValue}: ${message}`);
    } catch (error) {
      if (error?.name === "AbortError") {
        console.warn(`Graph warmup timed out for ${frameworkValue}. Proceeding with direct query path.`);
      } else {
        console.warn(`Graph warmup skipped for ${frameworkValue}:`, error);
      }
    } finally {
      window.clearTimeout(timeoutId);
      endGraphWarmup();
    }
  }

  async function loadCustomers(preferredCustomerId) {
    try {
      const response = await fetch(`${ingestionApiUrl}/customers`);
      const data = await parseResponse(response);

      if (!response.ok) {
        throw new Error(data.message || `Unable to load customers (${response.status})`);
      }

      if (!Array.isArray(data) || data.length === 0) {
        setCustomers([]);
        setCustomer("");
        setCustomerStatus("Ingestion service is reachable, but no customers exist yet. Create one below.");
        return;
      }

      const nextCustomers = data
        .filter((entry) => entry.documentCount > 0)
        .map((entry) => ({
          customerId: entry.customerId,
          name: entry.name || entry.customerId,
        }));

      if (nextCustomers.length === 0) {
        setCustomers([]);
        setCustomer("");
        setCustomerStatus("No customers with ingested documents found. Upload FAQ data first.");
        return;
      }

      setCustomers(nextCustomers);
      setCustomer((currentCustomer) => {
        const targetCustomerId = preferredCustomerId || currentCustomer;
        return nextCustomers.some((entry) => entry.customerId === targetCustomerId)
          ? targetCustomerId
          : nextCustomers[0].customerId;
      });
      setCustomerStatus(`Loaded ${nextCustomers.length} customer${nextCustomers.length === 1 ? "" : "s"} with data from the ingestion service.`);
    } catch (requestError) {
      setCustomers(fallbackCustomers);
      setCustomer((currentCustomer) => currentCustomer || fallbackCustomers[0].customerId);
      setCustomerStatus(requestError.message || "Could not reach the ingestion service. Using the local fallback customer list.");
    }
  }

  async function loadCustomerDocuments() {
    if (!Array.isArray(customers) || customers.length === 0) {
      setCustomerDocuments([]);
      setDocumentsStatus("No customers available to inspect indexed FAQ documents.");
      return;
    }

    setDocumentsLoading(true);

    try {
      const snapshots = await Promise.allSettled(
        customers.map(async (entry) => {
          const response = await fetch(`${ingestionApiUrl}/customers/${entry.customerId}/documents`);
          const data = await parseResponse(response);

          if (!response.ok) {
            throw new Error(data.message || `Unable to load documents (${response.status})`);
          }

          const documents = Array.isArray(data) ? data : [];
          return documents.map((docEntry) => ({
            ...docEntry,
            customerCode: entry.customerId,
            customerName: entry.name || entry.customerId,
          }));
        })
      );

      const flattenedDocuments = snapshots
        .filter((snapshot) => snapshot.status === "fulfilled")
        .flatMap((snapshot) => snapshot.value);
      const failedCount = snapshots.filter((snapshot) => snapshot.status === "rejected").length;

      setCustomerDocuments(flattenedDocuments);
      if (flattenedDocuments.length === 0) {
        setDocumentsStatus("No indexed FAQ documents found across customers.");
      } else if (failedCount > 0) {
        setDocumentsStatus(`Loaded ${flattenedDocuments.length} document${flattenedDocuments.length === 1 ? "" : "s"} with partial errors.`);
      } else {
        setDocumentsStatus(`Loaded ${flattenedDocuments.length} document${flattenedDocuments.length === 1 ? "" : "s"} across ${customers.length} customer${customers.length === 1 ? "" : "s"}.`);
      }
    } catch (requestError) {
      setCustomerDocuments([]);
      setDocumentsStatus(requestError.message || "Failed to load indexed documents.");
    } finally {
      setDocumentsLoading(false);
    }
  }

  useEffect(() => {
    if (!error) {
      return;
    }

    setError("");
  }, [question, imageDescription, mode, selectedServiceId, framework, customer, uploadedImage]);

  useEffect(() => {
    if (needsImageContext) {
      return;
    }

    setUploadedImage(null);
    if (imageInputRef.current) {
      imageInputRef.current.value = "";
    }
  }, [needsImageContext]);

  useEffect(() => {
    loadCustomers(customer);
  }, []);

  useEffect(() => {
    if (!documentManagerOpen) {
      return;
    }

    loadCustomerDocuments();
  }, [documentManagerOpen, customerIdsAnalyticsKey]);

  useEffect(() => {
    loadAnalyticsDashboard();
  }, [customerIdsAnalyticsKey, transcript.length]);

  function extractMetaValue(meta, label) {
    return meta.find((entry) => entry.label === label)?.value;
  }

  function classifyResponseSource(strategyValue) {
    const strategy = String(strategyValue || "").toLowerCase();

    if (!strategy) {
      return "Unknown";
    }

    if (strategy.includes("pattern-registry+structured-extraction") || strategy.includes("deterministic-extraction")) {
      return "Deterministic";
    }

    if (
      strategy.includes("langchain-agent") ||
      strategy.includes("langgraph-routing") ||
      strategy.includes("agent-plan") ||
      strategy.includes("semantic-intent")
    ) {
      return "LLM";
    }

    return "Mixed";
  }

  function clamp01(value) {
    return Math.max(0, Math.min(1, value));
  }

  function deriveAnalyticsMetrics(result) {
    const chunksUsed = Number(extractMetaValue(result.meta, "Chunks Used") ?? 0);
    const graphFacts = Number(extractMetaValue(result.meta, "Graph Facts") ?? 0);
    const blocked = String(extractMetaValue(result.meta, "Blocked") ?? "No").toLowerCase() === "yes";
    const retrievalQuality = clamp01((chunksUsed + graphFacts) / 6 || (result.status === "success" ? 0.65 : 0.2));
    const groundedCorrectness = clamp01(result.status === "success" ? 0.78 : 0.25);
    const safety = clamp01(blocked ? 0.45 : 0.9);
    const latencyEfficiency = clamp01(1200 / Math.max(1, result.latencyMs));

    const scale = result.latencyMs / 1200;

    return {
      retrievalQuality,
      groundedCorrectness,
      safety,
      latencyEfficiency,
      queryParseMs: Math.round(40 * scale),
      retrievalMs: Math.round(120 * scale),
      rerankMs: Math.round(160 * scale),
      generationMs: Math.round(800 * scale),
      postChecksMs: Math.round(80 * scale),
    };
  }

  async function persistAnalyticsEvents({ requestId, modeValue, prompt, ragPattern, customerId, results }) {
    try {
      const events = results.map((result) => {
        const metrics = deriveAnalyticsMetrics(result);
        return {
          requestId,
          mode: modeValue,
          query: prompt,
          response: result.answer,
          customer: customerId,
          ragPattern,
          framework: result.frameworkLabel || result.serviceLabel,
          strategy: result.strategy || extractMetaValue(result.meta, "Strategy") || "",
          status: result.status,
          latencyMs: result.latencyMs,
          ...metrics,
        };
      });

      await fetch(`${analyticsApiUrl}/events`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ events }),
      });
    } catch {
      // Analytics should never block the main query flow.
    }
  }

  async function loadAnalyticsDashboard() {
    setDashboardLoading(true);
    setDashboardError("");

    try {
      const ragPatterns = services.map((service) => service.label);
      const requests = customerIdsForAnalytics.flatMap((customerIdValue) =>
        ragPatterns.map((ragPatternValue) => ({
          customerId: customerIdValue,
          ragPattern: ragPatternValue,
        }))
      );

      const results = await Promise.allSettled(
        requests.map(async ({ customerId, ragPattern }) => {
          const queryParams = new URLSearchParams({
            ragPattern,
            customer: customerId,
            limit: "20",
          });
          const response = await fetch(`${analyticsApiUrl}/dashboard?${queryParams.toString()}`);
          const data = await parseResponse(response);

          if (!response.ok) {
            throw new Error(data.message || `Unable to load analytics (${response.status})`);
          }

          return {
            customerId,
            ragPattern,
            leaderboard: Array.isArray(data.leaderboard) ? data.leaderboard : [],
            recent: Array.isArray(data.recent) ? data.recent : [],
          };
        })
      );

      const successful = results
        .filter((entry) => entry.status === "fulfilled")
        .map((entry) => entry.value);
      const failed = results.filter((entry) => entry.status === "rejected");

      if (successful.length === 0) {
        throw new Error("Analytics dashboard is currently unavailable.");
      }

      const detailRows = [];
      const aggregateByFramework = new Map();
      const allRecentRuns = [];

      successful.forEach((snapshot) => {
        snapshot.leaderboard.forEach((row) => {
          const totalRuns = Number(row.totalRuns ?? 0);
          const runWeight = Math.max(totalRuns, 1);
          const avgLatencyMs = Number(row.avgLatencyMs ?? 0);
          const successRate = Number(row.successRate ?? 0);
          const avgEffectiveRagScore = Number(row.avgEffectiveRagScore ?? 0);

          detailRows.push({
            customer: snapshot.customerId,
            ragPattern: snapshot.ragPattern,
            framework: row.framework,
            totalRuns,
            successRate,
            avgLatencyMs,
            avgEffectiveRagScore,
          });

          const currentAggregate = aggregateByFramework.get(row.framework) || {
            framework: row.framework,
            runWeight: 0,
            weightedSuccess: 0,
            weightedLatency: 0,
            weightedEffectiveRag: 0,
          };

          currentAggregate.runWeight += runWeight;
          currentAggregate.weightedSuccess += successRate * runWeight;
          currentAggregate.weightedLatency += avgLatencyMs * runWeight;
          currentAggregate.weightedEffectiveRag += avgEffectiveRagScore * runWeight;
          aggregateByFramework.set(row.framework, currentAggregate);
        });

        snapshot.recent.forEach((row) => {
          allRecentRuns.push({
            ...row,
            customer: snapshot.customerId,
            ragPattern: snapshot.ragPattern,
          });
        });
      });

      const nextSummary = Array.from(aggregateByFramework.values())
        .map((entry) => ({
          framework: entry.framework,
          totalRuns: entry.runWeight,
          successRate: entry.runWeight ? entry.weightedSuccess / entry.runWeight : 0,
          avgLatencyMs: entry.runWeight ? entry.weightedLatency / entry.runWeight : 0,
          avgEffectiveRagScore: entry.runWeight ? entry.weightedEffectiveRag / entry.runWeight : 0,
        }))
        .sort((leftRow, rightRow) => rightRow.avgEffectiveRagScore - leftRow.avgEffectiveRagScore);

      setDashboardSummary(nextSummary);
      setDashboardRows(
        detailRows.sort((leftRow, rightRow) => {
          const patternCompare = leftRow.ragPattern.localeCompare(rightRow.ragPattern);
          if (patternCompare !== 0) {
            return patternCompare;
          }
          const customerCompare = leftRow.customer.localeCompare(rightRow.customer);
          if (customerCompare !== 0) {
            return customerCompare;
          }
          return leftRow.framework.localeCompare(rightRow.framework);
        })
      );
      setRecentRuns(
        allRecentRuns
          .sort((leftRow, rightRow) => {
            const leftTime = new Date(leftRow.createdAt || 0).getTime();
            const rightTime = new Date(rightRow.createdAt || 0).getTime();
            return rightTime - leftTime;
          })
          .slice(0, 60)
      );

      if (failed.length > 0) {
        setDashboardError(`Loaded partial analytics data (${successful.length}/${results.length} views).`);
      }

      // Fetch score distribution data
      try {
        const distResponse = await fetch(`${analyticsApiUrl}/score-distribution?days=7`);
        if (distResponse.ok) {
          const distData = await distResponse.json();
          setScoreDistribution(distData);
        }
      } catch {
        // Score distribution is optional — don't block dashboard
      }
    } catch (requestError) {
      setDashboardRows([]);
      setDashboardSummary([]);
      setRecentRuns([]);
      setScoreDistribution(null);
      setDashboardError(requestError.message || "Analytics dashboard is currently unavailable.");
    } finally {
      setDashboardLoading(false);
    }
  }

  function formatCsvValue(value) {
    const normalized = String(value ?? "").replace(/"/g, '""');
    return `"${normalized}"`;
  }

  function exportAnalyticsMatrix() {
    if (filteredDashboardRows.length === 0) {
      return;
    }

    const header = [
      "ragPattern",
      "customer",
      "framework",
      "totalRuns",
      "successRate",
      "avgLatencyMs",
      "avgEffectiveRagScore",
      "isPreferredInSegment",
    ];

    const lines = [header.map(formatCsvValue).join(",")];

    filteredDashboardRows.forEach((row) => {
      const preferredKey = `${row.ragPattern}||${row.customer}||${row.framework}`;
      lines.push(
        [
          row.ragPattern,
          row.customer,
          row.framework,
          row.totalRuns,
          row.successRate,
          row.avgLatencyMs,
          row.avgEffectiveRagScore,
          preferredTechBySegment.has(preferredKey) ? "yes" : "no",
        ]
          .map(formatCsvValue)
          .join(",")
      );
    });

    const fileBlob = new Blob([`${lines.join("\n")}\n`], {
      type: "text/csv;charset=utf-8",
    });
    const url = URL.createObjectURL(fileBlob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "analytics-matrix-filtered.csv";
    link.click();
    URL.revokeObjectURL(url);
  }

  function applyQuickAnalyticsFilters(chip) {
    setAnalyticsFrameworkFilter(chip.framework);
    setAnalyticsPatternFilter(chip.pattern);
  }

  function saveAnalyticsPreset() {
    const presetName = newAnalyticsPresetName.trim();
    if (!presetName) {
      return;
    }

    setAnalyticsPresets((currentPresets) => {
      const filtered = currentPresets.filter((entry) => entry.name.toLowerCase() !== presetName.toLowerCase());
      return [
        ...filtered,
        {
          name: presetName,
          ...currentAnalyticsFilterState,
        },
      ].sort((left, right) => left.name.localeCompare(right.name));
    });
    setNewAnalyticsPresetName("");
  }

  function applyAnalyticsPreset(preset) {
    setAnalyticsCustomerFilter(preset.customer || "all");
    setAnalyticsFrameworkFilter(preset.framework || "all");
    setAnalyticsPatternFilter(preset.pattern || "all");
    setAnalyticsSearchTerm(preset.search || "");
  }

  function deleteAnalyticsPreset(presetName) {
    setAnalyticsPresets((currentPresets) =>
      currentPresets.filter((entry) => entry.name !== presetName)
    );
  }

  function exportAnalyticsPresets() {
    if (analyticsPresets.length === 0) {
      return;
    }

    const fileBlob = new Blob([`${JSON.stringify(analyticsPresets, null, 2)}\n`], {
      type: "application/json;charset=utf-8",
    });
    const url = URL.createObjectURL(fileBlob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "analytics-filter-presets.json";
    link.click();
    URL.revokeObjectURL(url);
  }

  function triggerPresetImport() {
    presetImportInputRef.current?.click();
  }

  async function handlePresetImport(event) {
    const selectedFile = event.target.files?.[0];
    if (!selectedFile) {
      return;
    }

    try {
      const rawContent = await selectedFile.text();
      const parsed = JSON.parse(rawContent);
      if (!Array.isArray(parsed)) {
        throw new Error("Preset file must contain an array.");
      }

      const importedPresets = parsed
        .filter((entry) => entry && typeof entry.name === "string")
        .map((entry) => ({
          name: entry.name,
          customer: entry.customer || "all",
          framework: entry.framework || "all",
          pattern: entry.pattern || "all",
          search: entry.search || "",
        }));

      if (importedPresets.length === 0) {
        throw new Error("Preset file did not contain any valid entries.");
      }

      setAnalyticsPresets((currentPresets) => {
        const merged = [...currentPresets];
        importedPresets.forEach((incomingPreset) => {
          const existingIndex = merged.findIndex(
            (entry) => entry.name.toLowerCase() === incomingPreset.name.toLowerCase()
          );

          if (existingIndex >= 0) {
            merged[existingIndex] = incomingPreset;
          } else {
            merged.push(incomingPreset);
          }
        });

        return merged.sort((left, right) => left.name.localeCompare(right.name));
      });
      setError("");
    } catch (importError) {
      setError(importError.message || "Failed to import presets.");
    } finally {
      event.target.value = "";
    }
  }

  function resetAnalyticsFilters() {
    setAnalyticsCustomerFilter("all");
    setAnalyticsFrameworkFilter("all");
    setAnalyticsPatternFilter("all");
    setAnalyticsSearchTerm("");
  }

  useEffect(() => {
    try {
      const rawValue = window.localStorage.getItem(analyticsPresetsStorageKey);
      if (!rawValue) {
        return;
      }

      const parsed = JSON.parse(rawValue);
      if (!Array.isArray(parsed)) {
        return;
      }

      const nextPresets = parsed
        .filter((entry) => entry && typeof entry.name === "string")
        .map((entry) => ({
          name: entry.name,
          customer: entry.customer || "all",
          framework: entry.framework || "all",
          pattern: entry.pattern || "all",
          search: entry.search || "",
        }));

      setAnalyticsPresets(nextPresets);
    } catch {
      setAnalyticsPresets([]);
    }
  }, []);

  useEffect(() => {
    try {
      window.localStorage.setItem(analyticsPresetsStorageKey, JSON.stringify(analyticsPresets));
    } catch {
      // Ignore storage errors.
    }
  }, [analyticsPresets]);

  function normalizeMeta(serviceId, data) {
    const metaEntries = [];

    if (typeof data.chunksUsed === "number") {
      metaEntries.push({ label: "Chunks Used", value: data.chunksUsed });
    }
    if (data.transformedQuery) {
      metaEntries.push({ label: "Transformed Query", value: data.transformedQuery });
    }
    if (typeof data.grounded === "boolean") {
      metaEntries.push({ label: "Grounded", value: data.grounded ? "Yes" : "No" });
    }
    if (typeof data.retrievalLatencyMs === "number") {
      metaEntries.push({ label: "Retrieval Latency", value: `${data.retrievalLatencyMs} ms` });
    }
    if (typeof data.generationLatencyMs === "number") {
      metaEntries.push({ label: "Generation Latency", value: `${data.generationLatencyMs} ms` });
    }
    if (typeof data.vectorChunks === "number") {
      metaEntries.push({ label: "Vector Chunks", value: data.vectorChunks });
    }
    if (typeof data.graphFacts === "number") {
      metaEntries.push({ label: "Graph Facts", value: data.graphFacts });
    }
    if (data.selectedSection) {
      metaEntries.push({ label: "Section", value: data.selectedSection });
    }
    if (data.strategy) {
      metaEntries.push({ label: "Strategy", value: data.strategy });
    }
    if (data.consistencyLabel) {
      metaEntries.push({ label: "Image/Text Match", value: data.consistencyLabel });
    }
    if (typeof data.consistencyScore === "number") {
      metaEntries.push({ label: "Match Score", value: data.consistencyScore.toFixed(2) });
    }
    if (Array.isArray(data.consistencyReasons) && data.consistencyReasons.length > 0) {
      metaEntries.push({ label: "Consistency Notes", value: data.consistencyReasons.join("; ") });
    }
    if (typeof data.blocked === "boolean") {
      metaEntries.push({ label: "Blocked", value: data.blocked ? "Yes" : "No" });
    }
    if (data.reason) {
      metaEntries.push({ label: "Reason", value: data.reason });
    }
    if (serviceId === "multimodal" && imageDescription.trim()) {
      metaEntries.push({ label: "Image Context", value: imageDescription.trim() });
    }
    if (serviceId === "multimodal" && uploadedImage) {
      metaEntries.push({
        label: "Uploaded Image",
        value: `${uploadedImage.name} (${Math.round(uploadedImage.size / 1024)} KB)`,
      });
    }

    return metaEntries;
  }

  async function runQuery(service, prompt, overrideUrl) {
    const startedAt = performance.now();
    const baseUrl = overrideUrl ?? resolveUrl(framework, service.id);

    try {
      const isMultimodalUpload = service.id === "multimodal" && uploadedImage;
      let response;

      if (isMultimodalUpload) {
        const formData = new FormData();
        formData.append("question", prompt);
        formData.append("customerId", trimmedCustomerId);
        formData.append("imageDescription", imageDescription);
        formData.append("image", uploadedImage);

        response = await fetch(`${baseUrl}/query/ask-with-image`, {
          method: "POST",
          body: formData,
        });
      } else {
        let endpoint = `${baseUrl}/query/ask`;
        let payload;

        if (service.id === "retrieval") {
          endpoint = `${baseUrl}/retrieval/query`;
          payload = {
            tenantId: trimmedCustomerId,
            question: prompt,
            topK: 4,
            similarityThreshold: 0.35,
          };
        } else if (service.id === "multimodal") {
          payload = { question: prompt, customerId: trimmedCustomerId, imageDescription };
        } else {
          payload = { question: prompt, customerId: trimmedCustomerId };
        }

        response = await fetch(endpoint, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
        });
      }

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.message || `Request failed with status ${response.status}`);
      }

      return {
        serviceId: service.id,
        serviceLabel: service.label,
        frameworkLabel: frameworkOptions.find((option) => option.value === framework)?.label || "Spring AI",
        answer: data.answer || "No answer returned.",
        strategy: data.strategy || data.orchestrationStrategy || "",
        rawData: data,
        meta: normalizeMeta(service.id, data),
        latencyMs: Math.round(performance.now() - startedAt),
        status: "success",
      };
    } catch (requestError) {
      return {
        serviceId: service.id,
        serviceLabel: service.label,
        frameworkLabel: frameworkOptions.find((option) => option.value === framework)?.label || "Spring AI",
        answer: requestError.message || "Unable to reach backend.",
        strategy: "",
        rawData: null,
        meta: [],
        latencyMs: Math.round(performance.now() - startedAt),
        status: "error",
      };
    }
  }

  async function runQueryStream(service, prompt, requestId, resultIndex, overrideUrl) {
    const startedAt = performance.now();
    const baseUrl = overrideUrl ?? resolveUrl(framework, service.id);
    const fwLabel = frameworkOptions.find((option) => option.value === framework)?.label || "Spring AI";

    try {
      const isMultimodalUpload = service.id === "multimodal" && uploadedImage;
      if (isMultimodalUpload) {
        return runQuery(service, prompt, overrideUrl);
      }

      let endpoint = service.id === "retrieval"
        ? `${baseUrl}/retrieval/query-stream`
        : `${baseUrl}/query/ask-stream`;
      let payload;

      if (service.id === "retrieval") {
        payload = { tenantId: trimmedCustomerId, question: prompt, topK: 4, similarityThreshold: 0.35 };
      } else if (service.id === "multimodal") {
        payload = { question: prompt, customerId: trimmedCustomerId, imageDescription };
      } else {
        payload = { question: prompt, customerId: trimmedCustomerId };
      }

      const response = await fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      if (!response.ok) {
        const errText = await response.text();
        throw new Error(errText || `Request failed with status ${response.status}`);
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let answer = "";
      let meta = {};

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() || "";

        for (const line of lines) {
          if (line.startsWith("event:")) {
            var currentEvent = line.slice(6).trim();
          } else if (line.startsWith("data:") && currentEvent) {
            const data = line.slice(5);
            if (currentEvent === "meta") {
              try { meta = JSON.parse(data); } catch {}
            } else if (currentEvent === "token") {
              answer += data;
              setTranscript((prev) => prev.map((turn) => {
                if (turn.id !== requestId) return turn;
                const updatedResults = [...turn.results];
                updatedResults[resultIndex] = {
                  ...updatedResults[resultIndex],
                  answer,
                  status: "streaming",
                };
                return { ...turn, results: updatedResults };
              }));
            } else if (currentEvent === "done") {
              // final
            }
            currentEvent = null;
          }
        }
      }

      const result = {
        serviceId: service.id,
        serviceLabel: service.label,
        frameworkLabel: fwLabel,
        answer: answer || "No answer returned.",
        strategy: meta.strategy || meta.orchestrationStrategy || "",
        rawData: meta,
        meta: normalizeMeta(service.id, meta),
        latencyMs: Math.round(performance.now() - startedAt),
        status: "success",
      };

      setTranscript((prev) => prev.map((turn) => {
        if (turn.id !== requestId) return turn;
        const updatedResults = [...turn.results];
        updatedResults[resultIndex] = result;
        return { ...turn, results: updatedResults };
      }));

      return result;
    } catch (requestError) {
      const result = {
        serviceId: service.id,
        serviceLabel: service.label,
        frameworkLabel: fwLabel,
        answer: requestError.message || "Unable to reach backend.",
        strategy: "",
        rawData: null,
        meta: [],
        latencyMs: Math.round(performance.now() - startedAt),
        status: "error",
      };
      setTranscript((prev) => prev.map((turn) => {
        if (turn.id !== requestId) return turn;
        const updatedResults = [...turn.results];
        updatedResults[resultIndex] = result;
        return { ...turn, results: updatedResults };
      }));
      return result;
    }
  }

  async function runCompare(prompt) {
    // Fire all three framework backends in parallel for the selected RAG pattern.
    return Promise.all(
      compareFrameworks.map(async (fw) => {
        const startedAt = performance.now();
        try {
          const isMultimodalUpload = selectedService.id === "multimodal" && uploadedImage;
          let response;

          if (isMultimodalUpload) {
            const formData = new FormData();
            formData.append("question", prompt);
            formData.append("customerId", trimmedCustomerId);
            formData.append("imageDescription", imageDescription);
            formData.append("image", uploadedImage);

            response = await fetch(`${fw.url}/query/ask-with-image`, {
              method: "POST",
              body: formData,
            });
          } else {
            let endpoint = `${fw.url}/query/ask`;
            let payload;

            if (selectedService.id === "retrieval") {
              endpoint = `${fw.url}/retrieval/query`;
              payload = {
                tenantId: trimmedCustomerId,
                question: prompt,
                topK: 4,
                similarityThreshold: 0.35,
              };
            } else if (selectedService.id === "multimodal") {
              payload = { question: prompt, customerId: trimmedCustomerId, imageDescription };
            } else {
              payload = { question: prompt, customerId: trimmedCustomerId };
            }

            response = await fetch(endpoint, {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify(payload),
            });
          }

          const data = await response.json();
          if (!response.ok) throw new Error(data.message || `Status ${response.status}`);
          return {
            serviceId: `${selectedService.id}-${fw.value}`,
            serviceLabel: fw.label,
            frameworkLabel: fw.label,
            answer: data.answer || "No answer returned.",
            strategy: data.strategy || data.orchestrationStrategy || "",
            rawData: data,
            meta: [
              { label: "RAG Pattern", value: selectedService.label },
              { label: "Customer", value: customer },
              ...normalizeMeta(selectedService.id, data),
            ],
            latencyMs: Math.round(performance.now() - startedAt),
            status: "success",
          };
        } catch (err) {
          return {
            serviceId: `${selectedService.id}-${fw.value}`,
            serviceLabel: fw.label,
            frameworkLabel: fw.label,
            answer: err.message || "Unable to reach backend.",
            strategy: "",
            rawData: null,
            meta: [
              { label: "RAG Pattern", value: selectedService.label },
              { label: "Customer", value: customer },
            ],
            latencyMs: Math.round(performance.now() - startedAt),
            status: "error",
          };
        }
      })
    );
  }

  async function askQuestion(event) {
    event?.preventDefault();
    if (!hasQuestion || loading) {
      return;
    }

    setLoading(true);
    setError("");

    try {
      const requestId = crypto.randomUUID();
      let results;
      if (mode === "compare") {
        results = await runCompare(trimmedQuestion);
        setTranscript((currentTranscript) => [
          ...currentTranscript,
          {
            id: requestId,
            question: trimmedQuestion,
            customer,
            framework,
            ragPattern: selectedService.label,
            mode,
            results,
          },
        ]);
      } else {
        // Streaming mode for single-service queries
        const placeholders = activeServices.map((service) => ({
          serviceId: service.id,
          serviceLabel: service.label,
          frameworkLabel: frameworkOptions.find((o) => o.value === framework)?.label || "Spring AI",
          answer: "",
          strategy: "",
          rawData: null,
          meta: [],
          latencyMs: 0,
          status: "streaming",
        }));
        setTranscript((currentTranscript) => [
          ...currentTranscript,
          {
            id: requestId,
            question: trimmedQuestion,
            customer,
            framework,
            ragPattern: selectedService.label,
            mode,
            results: placeholders,
          },
        ]);
        results = await Promise.all(
          activeServices.map((service, idx) => runQueryStream(service, trimmedQuestion, requestId, idx))
        );
      }
      await persistAnalyticsEvents({
        requestId,
        modeValue: mode,
        prompt: trimmedQuestion,
        ragPattern: selectedService.label,
        customerId: customer,
        results,
      });
    } catch (requestError) {
      setError(requestError.message || "Failed to run query.");
    } finally {
      setLoading(false);
    }
  }

  function startNewConversation() {
    setTranscript([]);
    setError("");
    setUploadedFaqName("");
    setSelectedFaqFiles([]);
    setFaqUploadStatus("");
    setFaqUploadResult(null);
    setCustomerDocuments([]);
    setDocumentsStatus("");
    setUploadedImage(null);
    if (imageInputRef.current) {
      imageInputRef.current.value = "";
    }
    if (fileInputRef.current) {
      fileInputRef.current.value = "";
    }
  }

  function exportTranscript() {
    const transcriptBlob = new Blob([JSON.stringify(transcript, null, 2)], {
      type: "application/json",
    });
    const downloadUrl = URL.createObjectURL(transcriptBlob);
    const link = document.createElement("a");
    link.href = downloadUrl;
    link.download = "faq-transcript.json";
    link.click();
    URL.revokeObjectURL(downloadUrl);
  }

  const allowedFaqExtensions = ["pdf", "md", "yaml", "yml", "doc", "docx", "txt", "png", "jpg", "jpeg"];

  function validateAndAddFiles(fileList) {
    const files = Array.from(fileList);
    const valid = [];
    const rejected = [];
    for (const file of files) {
      const ext = file.name.split(".").pop()?.toLowerCase();
      if (ext && allowedFaqExtensions.includes(ext)) {
        valid.push(file);
      } else {
        rejected.push(file.name);
      }
    }
    if (rejected.length > 0) {
      setError(`Unsupported file type(s): ${rejected.join(", ")}`);
    } else {
      setError("");
    }
    if (valid.length > 0) {
      setSelectedFaqFiles((prev) => [...prev, ...valid]);
      setFaqUploadStatus("");
      setFaqUploadResult(null);
    }
  }

  function handleFaqSelection(event) {
    const files = event.target.files;
    if (!files || files.length === 0) return;
    validateAndAddFiles(files);
    event.target.value = "";
  }

  function handleFaqDrop(event) {
    event.preventDefault();
    setFaqDragActive(false);
    const files = event.dataTransfer.files;
    if (!files || files.length === 0) return;
    validateAndAddFiles(files);
  }

  function handleFaqDragOver(event) {
    event.preventDefault();
    setFaqDragActive(true);
  }

  function handleFaqDragLeave() {
    setFaqDragActive(false);
  }

  function removeFaqFile(index) {
    setSelectedFaqFiles((prev) => prev.filter((_, i) => i !== index));
  }

  async function handleFaqUpload() {
    if (selectedFaqFiles.length === 0 || uploadingFaq) return;

    setUploadingFaq(true);
    setError("");
    const totalFiles = selectedFaqFiles.length;
    const results = [];

    for (let i = 0; i < totalFiles; i++) {
      const file = selectedFaqFiles[i];
      setFaqUploadStatus(`Uploading ${i + 1}/${totalFiles}: ${file.name}...`);

      try {
        const formData = new FormData();
        formData.append("file", file);

        const response = await fetch(`${ingestionApiUrl}/documents/upload/auto-customer`, {
          method: "POST",
          body: formData,
        });
        const data = await parseResponse(response);

        if (!response.ok) {
          throw new Error(data.message || data.processingError || `Upload failed with status ${response.status}`);
        }

        const detectedCustomerId = data.customerId || data.customer?.customerId || "";
        const detectedCustomerName = data.customerName || data.customer?.name || detectedCustomerId;
        const documentPayload = data.document || data;
        results.push({ file: file.name, status: "success", customer: detectedCustomerName, chunks: documentPayload.indexedChunkCount ?? documentPayload.chunkCount ?? 0 });

        if (detectedCustomerId) {
          setCustomer(detectedCustomerId);
          graphCustomerDataCacheRef.current.delete(detectedCustomerId);
          graphRebuildCacheRef.current.delete(`graph::${detectedCustomerId}`);
        }
      } catch (requestError) {
        results.push({ file: file.name, status: "error", error: requestError.message });
      }
    }

    const successCount = results.filter((r) => r.status === "success").length;
    const failCount = results.filter((r) => r.status === "error").length;
    setFaqUploadResult(results);
    setFaqUploadStatus(`Done: ${successCount} uploaded${failCount > 0 ? `, ${failCount} failed` : ""}.`);
    setUploadedFaqName(`${successCount} file(s)`);
    setSelectedFaqFiles([]);
    if (fileInputRef.current) fileInputRef.current.value = "";

    await loadCustomers(customer);
    await loadCustomerDocuments();
    setUploadingFaq(false);
  }

  async function handleCreateCustomer(event) {
    event.preventDefault();
    if (!canCreateCustomer) {
      return;
    }

    setCustomerBusy(true);
    setError("");

    try {
      const response = await fetch(`${ingestionApiUrl}/customers`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          customerId: trimmedNewCustomerId,
          name: trimmedNewCustomerName,
          description: newCustomerDescription.trim(),
        }),
      });
      const data = await parseResponse(response);

      if (!response.ok) {
        throw new Error(data.message || `Failed to create customer (${response.status})`);
      }

      setNewCustomerId("");
      setNewCustomerName("");
      setNewCustomerDescription("");
      setCustomerStatus(`Created customer ${data.customerId}.`);
      await loadCustomers(data.customerId);
    } catch (requestError) {
      setError(requestError.message || "Failed to create customer.");
    } finally {
      setCustomerBusy(false);
    }
  }

  function handleImageSelection(event) {
    const selectedFile = event.target.files?.[0];
    if (!selectedFile) {
      setUploadedImage(null);
      return;
    }

    if (!selectedFile.type.startsWith("image/")) {
      setError("Please select a valid image file.");
      setUploadedImage(null);
      event.target.value = "";
      return;
    }

    const maxBytes = 5 * 1024 * 1024;
    if (selectedFile.size > maxBytes) {
      setError("Image must be 5MB or smaller.");
      setUploadedImage(null);
      event.target.value = "";
      return;
    }

    setUploadedImage(selectedFile);
  }

  function applyDocumentQuickFilters(chip) {
    setDocumentsStatusFilter(chip.status);
    setDocumentsTypeFilter(chip.type);
  }

  function saveDocumentPreset() {
    const presetName = newDocumentPresetName.trim();
    if (!presetName) {
      return;
    }

    setDocumentPresets((currentPresets) => {
      const filtered = currentPresets.filter((entry) => entry.name.toLowerCase() !== presetName.toLowerCase());
      return [
        ...filtered,
        {
          name: presetName,
          ...currentDocumentFilterState,
        },
      ].sort((left, right) => left.name.localeCompare(right.name));
    });
    setNewDocumentPresetName("");
  }

  function applyDocumentPreset(preset) {
    setDocumentsCustomerFilter(preset.customer || "all");
    setDocumentsStatusFilter(preset.status || "all");
    setDocumentsTypeFilter(preset.type || "all");
    setDocumentsSearchTerm(preset.search || "");
  }

  function deleteDocumentPreset(presetName) {
    setDocumentPresets((currentPresets) =>
      currentPresets.filter((entry) => entry.name !== presetName)
    );
  }

  function exportDocumentPresets() {
    if (documentPresets.length === 0) {
      return;
    }

    const fileBlob = new Blob([`${JSON.stringify(documentPresets, null, 2)}\n`], {
      type: "application/json;charset=utf-8",
    });
    const url = URL.createObjectURL(fileBlob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "document-filter-presets.json";
    link.click();
    URL.revokeObjectURL(url);
  }

  function triggerDocumentPresetImport() {
    documentPresetImportInputRef.current?.click();
  }

  async function handleDocumentPresetImport(event) {
    const selectedFile = event.target.files?.[0];
    if (!selectedFile) {
      return;
    }

    try {
      const rawContent = await selectedFile.text();
      const parsed = JSON.parse(rawContent);
      if (!Array.isArray(parsed)) {
        throw new Error("Preset file must contain an array.");
      }

      const importedPresets = parsed
        .filter((entry) => entry && typeof entry.name === "string")
        .map((entry) => ({
          name: entry.name,
          customer: entry.customer || "all",
          status: entry.status || "all",
          type: entry.type || "all",
          search: entry.search || "",
        }));

      if (importedPresets.length === 0) {
        throw new Error("Preset file did not contain any valid entries.");
      }

      setDocumentPresets((currentPresets) => {
        const merged = [...currentPresets];
        importedPresets.forEach((incomingPreset) => {
          const existingIndex = merged.findIndex(
            (entry) => entry.name.toLowerCase() === incomingPreset.name.toLowerCase()
          );

          if (existingIndex >= 0) {
            merged[existingIndex] = incomingPreset;
          } else {
            merged.push(incomingPreset);
          }
        });

        return merged.sort((left, right) => left.name.localeCompare(right.name));
      });
      setDocumentsStatus("Document presets imported successfully.");
    } catch (importError) {
      setDocumentsStatus(importError.message || "Failed to import document presets.");
    } finally {
      event.target.value = "";
    }
  }

  function resetDocumentFilters() {
    setDocumentsCustomerFilter("all");
    setDocumentsTypeFilter("all");
    setDocumentsStatusFilter("all");
    setDocumentsSearchTerm("");
  }

  useEffect(() => {
    if (selectedServiceId !== "graph") {
      return;
    }

    loadGraphTasks(10);
  }, [selectedServiceId, framework]);

  useEffect(() => {
    if (!graphWarmupStatus) {
      return;
    }

    const timer = window.setInterval(() => {
      loadGraphTasks(10, { silent: true });
    }, 2000);

    return () => {
      window.clearInterval(timer);
    };
  }, [graphWarmupStatus, framework]);

  useEffect(() => {
    try {
      const rawValue = window.localStorage.getItem(documentPresetsStorageKey);
      if (!rawValue) {
        return;
      }

      const parsed = JSON.parse(rawValue);
      if (!Array.isArray(parsed)) {
        return;
      }

      const nextPresets = parsed
        .filter((entry) => entry && typeof entry.name === "string")
        .map((entry) => ({
          name: entry.name,
          customer: entry.customer || "all",
          status: entry.status || "all",
          type: entry.type || "all",
          search: entry.search || "",
        }));

      setDocumentPresets(nextPresets);
    } catch {
      setDocumentPresets([]);
    }
  }, []);

  useEffect(() => {
    try {
      window.localStorage.setItem(documentPresetsStorageKey, JSON.stringify(documentPresets));
    } catch {
      // Ignore storage errors.
    }
  }, [documentPresets]);

  function exportDocumentInventoryCsv() {
    if (filteredCustomerDocuments.length === 0) {
      return;
    }

    const escapeCsv = (value) => `"${String(value ?? "").replace(/"/g, '""')}"`;
    const header = [
      "customer",
      "documentName",
      "fileType",
      "processingStatus",
      "detectedStructure",
      "indexedChunks",
      "sizeBytes",
      "indexedAt",
    ];
    const lines = [header.map(escapeCsv).join(",")];

    filteredCustomerDocuments.forEach((entry) => {
      lines.push(
        [
          entry.customerCode,
          entry.originalFileName,
          String(entry.fileType || "").toUpperCase(),
          entry.processingStatus,
          entry.detectedStructure || "",
          entry.indexedChunkCount ?? entry.chunkCount ?? 0,
          entry.fileSizeBytes || 0,
          entry.indexedAt || entry.createdAt || "",
        ]
          .map(escapeCsv)
          .join(",")
      );
    });

    const fileBlob = new Blob([`${lines.join("\n")}\n`], {
      type: "text/csv;charset=utf-8",
    });
    const url = URL.createObjectURL(fileBlob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "document-inventory-filtered.csv";
    link.click();
    URL.revokeObjectURL(url);
  }

  return (
    <div className="app-shell">
      <main className="workspace">
        <section className="hero-card">
          <div className="hero-header">
            <div className="hero-title-block">
              <h1>FAQ Assistant</h1>
              <p className="hero-copy">
                Choose Mode + RAG pattern + Framework + Customer, then ask your query.
              </p>
            </div>
            <div className="hero-actions">
              <button
                type="button"
                className={`analysis-btn${analyticsVisible ? " active" : ""}`}
                onClick={() => setAnalyticsVisible((v) => !v)}
              >
                {analyticsVisible ? "Hide Analysis" : "Analysis"}
              </button>
              <button
                type="button"
                className="faq-panel-toggle"
                onClick={() => setDocumentManagerOpen((v) => !v)}
                aria-expanded={documentManagerOpen}
                aria-controls="faq-upload-panel"
              >
                {documentManagerOpen ? "Close FAQ" : "FAQ"}
              </button>
            </div>
          </div>
        </section>

        <section className="toolbar-card">
          <div className="toolbar-grid">
            <label>
              <span>Mode</span>
              <select value={mode} onChange={(event) => setMode(event.target.value)}>
                <option value="compare">Compare all backends</option>
                <option value="single">Single backend</option>
              </select>
            </label>

            <label>
              <span>RAG Pattern</span>
              <select
                value={selectedServiceId}
                onChange={(event) => setSelectedServiceId(event.target.value)}
              >
                {services.map((service) => (
                  <option
                    key={service.id}
                    value={service.id}
                    disabled={!enabledServiceIds.has(service.id)}
                  >
                    {service.label}{enabledServiceIds.has(service.id) ? "" : " (Coming soon)"}
                  </option>
                ))}
              </select>
            </label>

            {mode !== "compare" && (
              <label>
                <span>Framework</span>
                <select value={framework} onChange={(event) => setFramework(event.target.value)}>
                  {frameworkOptions.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </label>
            )}

            <label>
              <span>Customer</span>
              <select value={customer} onChange={(event) => setCustomer(event.target.value)}>
                <option value="" disabled>Select Customer</option>
                {customers.map((option) => (
                  <option key={option.customerId} value={option.customerId}>
                    {option.name}
                  </option>
                ))}
              </select>
            </label>
          </div>

          {needsImageContext && (
            <label className="image-context-field">
              <span>Image Context</span>
              <input
                type="text"
                value={imageDescription}
                onChange={(event) => setImageDescription(event.target.value)}
                placeholder="Optional description for multimodal comparisons"
              />
            </label>
          )}

          {needsImageContext && (
            <label className="image-upload-field">
              <span>Upload Image (optional)</span>
              <input
                ref={imageInputRef}
                type="file"
                accept="image/*"
                onChange={handleImageSelection}
              />
            </label>
          )}

          <div className="toolbar-actions">
            <button type="button" className="accent-soft" onClick={askQuestion} disabled={!canAsk}>
              {primaryActionLabel}
            </button>
            <button type="button" className="accent-solid" onClick={startNewConversation} disabled={!canStartNewConversation}>
              New Chat
            </button>
            <button type="button" className="accent-solid" onClick={exportTranscript} disabled={!canExport}>
              Export
            </button>
          </div>

          <p className="supporting-note">{customerStatus}</p>
          {!searchEnabled && <p className="supporting-note">Selected RAG pattern is temporarily unavailable.</p>}
          {mode === "compare" && (
            <p className="supporting-note">
              Compare mode shows the selected RAG pattern for all frameworks for the selected customer.
            </p>
          )}
          {needsImageContext && uploadedImage && (
            <p className="supporting-note">
              Selected image: <strong>{uploadedImage.name}</strong> ({Math.round(uploadedImage.size / 1024)} KB)
            </p>
          )}
          {mode !== "compare" && (
            <p className="supporting-note">
              Framework is set to <strong>{frameworkOptions.find((f) => f.value === framework)?.label}</strong>. 
              {isDirectApiMode
                ? "API requests are sent directly to backend service ports (bypassing Kong)."
                : `API requests are routed through Kong gateway (${framework === "spring-ai" ? "/spring/*" : framework === "langchain" ? "/langchain/*" : "/langgraph/*"}).`}
            </p>
          )}
          {graphWarmupStatus && <p className="graph-status-banner">{graphWarmupStatus}</p>}
          {error && <p className="error-banner">{error}</p>}

        </section>

        {analyticsVisible && (
        <section className="analysis-side-panel open">
          <div className="dashboard-header">
            <div>
              <p className="section-label">Analytics Dashboard</p>
              <h2>All RAG Patterns x Frameworks x Customers</h2>
            </div>
            <button
              type="button"
              className="accent-soft"
              onClick={() => setAnalyticsVisible(false)}
            >
              Close
            </button>
          </div>

          <div className="toolbar-actions compact-actions analytics-actions">
            <button
              type="button"
              className="accent-soft"
              onClick={exportAnalyticsMatrix}
              disabled={filteredDashboardRows.length === 0}
            >
              Export CSV
            </button>
            <button type="button" className="accent-soft" onClick={resetAnalyticsFilters}>
              Reset filters
            </button>
            <button type="button" className="accent-soft" onClick={loadAnalyticsDashboard} disabled={dashboardLoading}>
              {dashboardLoading ? "Refreshing..." : "Refresh analytics"}
            </button>
          </div>

          <p className="supporting-note">
            Single-page dashboard for all customers and all RAG patterns. Use the scrollable tables to compare framework preference.
          </p>

          <div className="quick-filter-strip" role="group" aria-label="Analytics quick filters">
            {quickFilterChips.map((chip) => (
              <button
                key={chip.id}
                type="button"
                className={`quick-filter-chip ${activeQuickChipId === chip.id ? "active" : ""}`}
                onClick={() => applyQuickAnalyticsFilters(chip)}
                disabled={Boolean(chip.disabled)}
              >
                {chip.label}{chip.disabled ? " (Coming soon)" : ""}
              </button>
            ))}
          </div>

          <div className="analytics-filters">
            <label>
              <span>Customer</span>
              <select value={analyticsCustomerFilter} onChange={(event) => setAnalyticsCustomerFilter(event.target.value)}>
                <option value="all">All customers</option>
                {analyticsCustomers.map((entry) => (
                  <option key={entry} value={entry}>{entry}</option>
                ))}
              </select>
            </label>

            <label>
              <span>Framework</span>
              <select value={analyticsFrameworkFilter} onChange={(event) => setAnalyticsFrameworkFilter(event.target.value)}>
                <option value="all">All frameworks</option>
                {analyticsFrameworks.map((entry) => (
                  <option key={entry} value={entry}>{entry}</option>
                ))}
              </select>
            </label>

            <label>
              <span>RAG Pattern</span>
              <select value={analyticsPatternFilter} onChange={(event) => setAnalyticsPatternFilter(event.target.value)}>
                <option value="all">All patterns</option>
                {analyticsPatterns.map((entry) => (
                  <option key={entry} value={entry}>{entry}</option>
                ))}
              </select>
            </label>

            <label>
              <span>Search</span>
              <input
                type="text"
                value={analyticsSearchTerm}
                onChange={(event) => setAnalyticsSearchTerm(event.target.value)}
                placeholder="Pattern, customer, framework"
              />
            </label>
          </div>

          <div className="analytics-presets">
            <div className="analytics-preset-create">
              <input
                type="text"
                value={newAnalyticsPresetName}
                onChange={(event) => setNewAnalyticsPresetName(event.target.value)}
                placeholder="Save current filters as preset"
              />
              <button
                type="button"
                className="accent-soft"
                onClick={saveAnalyticsPreset}
                disabled={!newAnalyticsPresetName.trim()}
              >
                Save preset
              </button>
              <button
                type="button"
                className="accent-soft"
                onClick={exportAnalyticsPresets}
                disabled={analyticsPresets.length === 0}
              >
                Export presets
              </button>
              <button
                type="button"
                className="accent-soft"
                onClick={triggerPresetImport}
              >
                Import presets
              </button>
              <input
                ref={presetImportInputRef}
                type="file"
                accept="application/json,.json"
                className="hidden-input"
                onChange={handlePresetImport}
              />
            </div>

            {analyticsPresets.length > 0 && (
              <div className="analytics-preset-list">
                {analyticsPresets.map((preset) => (
                  <div key={preset.name} className="analytics-preset-item">
                    <button
                      type="button"
                      className="quick-filter-chip"
                      onClick={() => applyAnalyticsPreset(preset)}
                    >
                      {preset.name}
                    </button>
                    <button
                      type="button"
                      className="preset-delete-btn"
                      onClick={() => deleteAnalyticsPreset(preset.name)}
                      aria-label={`Delete preset ${preset.name}`}
                    >
                      x
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>

          <p className="analytics-summary-note">
            Showing {analyticsFilterSummary.rowCount} rows | {analyticsFilterSummary.frameworkCount} frameworks | {analyticsFilterSummary.patternCount} patterns | {analyticsFilterSummary.customerCount} customers
          </p>

          {dashboardError && <p className="error-banner">{dashboardError}</p>}

          <div className="analytics-grid">
            <div className="manager-panel">
              <p className="meta-title">Overall Framework Preference</p>
              {filteredDashboardSummary.length === 0 ? (
                <p className="supporting-note">No analytics runs available yet.</p>
              ) : (
                <div className="table-scroll table-scroll-y">
                  <table className="analytics-table">
                    <thead>
                      <tr>
                        <th>Framework</th>
                        <th>Runs</th>
                        <th>Success</th>
                        <th>Avg Latency</th>
                        <th>Effective RAG Score</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filteredDashboardSummary.map((row, index) => {
                        const isBestAverage = bestAverageFramework && row.framework === bestAverageFramework.framework;
                        const frameworkClassName = row.framework?.toLowerCase().replace(/\s+/g, "-");

                        return (
                        <tr
                          key={`${row.framework}-${index}`}
                          className={`${isBestAverage ? "best-framework-row" : ""} framework-row-${frameworkClassName}`.trim()}
                        >
                          <td>
                            {row.framework}
                            <span className="rank-chip">#{index + 1}</span>
                          </td>
                          <td>{row.totalRuns}</td>
                          <td>{(row.successRate * 100).toFixed(1)}%</td>
                          <td>{Math.round(row.avgLatencyMs)} ms</td>
                          <td>
                            {row.avgEffectiveRagScore.toFixed(3)}
                            {isBestAverage && <span className="best-framework-chip">Top Avg</span>}
                          </td>
                        </tr>
                      );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className="manager-panel">
              <p className="meta-title">Recent Runs</p>
              {filteredRecentRuns.length === 0 ? (
                <p className="supporting-note">No recent runs captured yet.</p>
              ) : (
                <div className="table-scroll table-scroll-y">
                  <table className="analytics-table">
                    <thead>
                      <tr>
                        <th>RAG Pattern</th>
                        <th>Customer</th>
                        <th>Framework</th>
                        <th>Status</th>
                        <th>Latency</th>
                        <th>Score</th>
                        <th>RQ</th>
                        <th>GC</th>
                        <th>Safety</th>
                        <th>Scored By</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filteredRecentRuns.map((row, index) => (
                        <tr key={`${row.framework}-${row.createdAt}-${index}`}>
                          <td>{row.ragPattern}</td>
                          <td>{row.customer}</td>
                          <td>{row.framework}</td>
                          <td>{row.status}</td>
                          <td>{row.latencyMs} ms</td>
                          <td>{Number(row.effectiveRagScore).toFixed(3)}</td>
                          <td>{row.retrievalQuality != null ? Number(row.retrievalQuality).toFixed(2) : "—"}</td>
                          <td>{row.groundedCorrectness != null ? Number(row.groundedCorrectness).toFixed(2) : "—"}</td>
                          <td>{row.safety != null ? Number(row.safety).toFixed(2) : "—"}</td>
                          <td>
                            <span className={`scoring-badge ${row.llmScored ? "llm-scored" : "heuristic-scored"}`}>
                              {row.llmScored ? "LLM" : "Heuristic"}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>

          <div className="manager-panel analytics-full-panel">
            <p className="meta-title">Detailed Matrix</p>
            {filteredDashboardRows.length === 0 ? (
              <p className="supporting-note">No detailed analytics rows available yet.</p>
            ) : (
              <div className="table-scroll table-scroll-y table-scroll-tall">
                <table className="analytics-table">
                  <thead>
                    <tr>
                      <th>RAG Pattern</th>
                      <th>Customer</th>
                      <th>Framework</th>
                      <th>Runs</th>
                      <th>Success</th>
                      <th>Avg Latency</th>
                      <th>Effective RAG Score</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredDashboardRows.map((row, index) => (
                      <tr
                        key={`${row.ragPattern}-${row.customer}-${row.framework}-${index}`}
                        className={preferredTechBySegment.has(`${row.ragPattern}||${row.customer}||${row.framework}`)
                          ? "segment-best-row"
                          : ""}
                      >
                        <td>{row.ragPattern}</td>
                        <td>{row.customer}</td>
                        <td>
                          {row.framework}
                          {preferredTechBySegment.has(`${row.ragPattern}||${row.customer}||${row.framework}`) && (
                            <span className="segment-best-chip">Preferred</span>
                          )}
                        </td>
                        <td>{row.totalRuns}</td>
                        <td>{(row.successRate * 100).toFixed(1)}%</td>
                        <td>{Math.round(row.avgLatencyMs)} ms</td>
                        <td>{row.avgEffectiveRagScore.toFixed(3)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {scoreDistribution && (
          <div className="analytics-grid">
            <div className="manager-panel">
              <p className="meta-title">Score Distribution (Last 7 Days)</p>
              {(!scoreDistribution.distribution || scoreDistribution.distribution.length === 0) ? (
                <p className="supporting-note">No score distribution data available.</p>
              ) : (
                <div className="score-distribution-chart">
                  {scoreDistribution.distribution.map((bucket) => {
                    const maxCount = Math.max(...scoreDistribution.distribution.map(b => b.count), 1);
                    const barWidth = Math.max((bucket.count / maxCount) * 100, 2);
                    return (
                      <div key={bucket.bucket} className="distribution-bar-row">
                        <span className="distribution-label">{bucket.bucket}</span>
                        <div className="distribution-bar-container">
                          <div
                            className="distribution-bar"
                            style={{ width: `${barWidth}%` }}
                          />
                        </div>
                        <span className="distribution-count">{bucket.count}</span>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            <div className="manager-panel">
              <p className="meta-title">Sub-Score Breakdown by Framework × Pattern</p>
              {(!scoreDistribution.subscoreBreakdown || scoreDistribution.subscoreBreakdown.length === 0) ? (
                <p className="supporting-note">No sub-score breakdown available.</p>
              ) : (
                <div className="table-scroll table-scroll-y">
                  <table className="analytics-table">
                    <thead>
                      <tr>
                        <th>Framework</th>
                        <th>Pattern</th>
                        <th>Retrieval</th>
                        <th>Grounded</th>
                        <th>Safety</th>
                        <th>Latency</th>
                        <th>Effective</th>
                        <th>LLM Scored</th>
                        <th>Heuristic</th>
                      </tr>
                    </thead>
                    <tbody>
                      {scoreDistribution.subscoreBreakdown.map((row, index) => (
                        <tr key={`${row.framework}-${row.ragPattern}-${index}`}>
                          <td>{row.framework}</td>
                          <td>{row.ragPattern}</td>
                          <td>{Number(row.avgRetrievalQuality).toFixed(3)}</td>
                          <td>{Number(row.avgGroundedCorrectness).toFixed(3)}</td>
                          <td>{Number(row.avgSafety).toFixed(3)}</td>
                          <td>{Number(row.avgLatencyEfficiency).toFixed(3)}</td>
                          <td><strong>{Number(row.avgEffectiveRagScore).toFixed(3)}</strong></td>
                          <td>
                            <span className={`scoring-badge ${row.llmScoredCount > 0 ? "llm-scored" : "heuristic-scored"}`}>
                              {row.llmScoredCount}
                            </span>
                          </td>
                          <td>{row.heuristicCount}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>
          )}
        </section>
        )}

        <section className="conversation-stack">
          {transcript.length === 0 && (
            <div className="empty-state">
              <p className="section-label">Conversation</p>
              <h2>No messages yet</h2>
              <p>
                Ask a policy question to render a single response or a multi-backend comparison.
              </p>
            </div>
          )}

          {transcript.map((turn) => (
            <article key={turn.id} className="turn-block">
              <section className="message-card user-card">
                <p className="section-label">You</p>
                <p className="message-text">{turn.question}</p>
                <p className="result-latency">
                  Mode: {turn.mode === "compare" ? "Compare all backends" : "Single backend"} | RAG:
                  {" "}{turn.ragPattern}
                  {" "}
                  | Framework: {turn.mode === "compare"
                    ? "All frameworks"
                    : frameworkOptions.find((f) => f.value === turn.framework)?.label || turn.framework}
                  {" "}
                  | Customer: {turn.customer}
                </p>
              </section>

              <section className="message-card assistant-card">
                <p className="section-label">Assistant</p>
                <div className={turn.results.length > 1 ? "result-grid" : "result-grid single-column"}>
                  {turn.results.map((result) => (
                    <div key={result.serviceId} className={`result-card ${result.status}`}>
                      <h3>{result.frameworkLabel || result.serviceLabel}</h3>
                      <p className="result-answer">{result.answer}{result.status === "streaming" ? "▌" : ""}</p>
                      <p className="result-latency">Latency: {result.latencyMs} ms</p>
                      {false && (
                        <div className="meta-block">
                          <p className="meta-title">Signals</p>
                          <ul>
                            {result.meta.map((entry) => (
                              <li key={`${result.serviceId}-${entry.label}`}>
                                <strong>{entry.label}:</strong> {entry.value}
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              </section>
            </article>
          ))}
        </section>

        <form className="composer" onSubmit={askQuestion}>
          <input
            type="text"
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            placeholder="Ask a question, for example: What is your return policy?"
          />
          <button type="submit" disabled={!canAsk}>{primaryActionLabel}</button>
        </form>
      </main>

      <aside id="faq-upload-panel" className={`faq-side-panel ${documentManagerOpen ? "open" : ""}`}>
        <div className="faq-side-panel-header">
          <p className="section-label">FAQ Upload</p>
          <button
            type="button"
            className="accent-soft"
            onClick={() => setDocumentManagerOpen(false)}
          >
            Close
          </button>
        </div>

        <p className="supporting-note">
          Upload FAQ documents (multiple files supported). Customer name is auto-detected using LLM and document heuristics.
        </p>

        <div
          className={`faq-drop-zone${faqDragActive ? " drag-active" : ""}`}
          onDrop={handleFaqDrop}
          onDragOver={handleFaqDragOver}
          onDragLeave={handleFaqDragLeave}
          onClick={() => fileInputRef.current?.click()}
        >
          <span className="faq-drop-icon">📂</span>
          <p>Drag &amp; drop files here</p>
          <p className="supporting-note">or click to browse</p>
          <input
            ref={fileInputRef}
            type="file"
            multiple
            accept=".pdf,.md,.yaml,.yml,.doc,.docx,.txt,.png,.jpg,.jpeg"
            onChange={handleFaqSelection}
            style={{ display: "none" }}
          />
        </div>

        <p className="supporting-note">
          Supported: PDF, Markdown, YAML, DOC/DOCX, TXT, PNG, JPG/JPEG
        </p>

        {selectedFaqFiles.length > 0 && (
          <div className="faq-file-list">
            <p className="supporting-note"><strong>{selectedFaqFiles.length} file(s) selected</strong></p>
            {selectedFaqFiles.map((file, idx) => (
              <div key={`${file.name}-${idx}`} className="faq-file-item">
                <span className="faq-file-name">{file.name}</span>
                <span className="faq-file-size">{Math.round(file.size / 1024)} KB</span>
                <button type="button" className="faq-file-remove" onClick={() => removeFaqFile(idx)} title="Remove">✕</button>
              </div>
            ))}
          </div>
        )}

        {faqUploadStatus && <p className="supporting-note">{faqUploadStatus}</p>}

        {Array.isArray(faqUploadResult) && faqUploadResult.length > 0 && (
          <div className="upload-summary">
            {faqUploadResult.map((r, i) => (
              <p key={i}>
                {r.status === "success"
                  ? `✓ ${r.file} — ${r.customer} (${r.chunks} chunks)`
                  : `✗ ${r.file} — ${r.error}`}
              </p>
            ))}
          </div>
        )}

        <div className="toolbar-actions compact-actions">
          <button type="button" className="accent-solid" onClick={handleFaqUpload} disabled={!canUploadFaq}>
            {uploadingFaq ? "Uploading..." : "Upload and Auto-Detect Customer"}
          </button>
          <button
            type="button"
            className="accent-soft"
            onClick={() => window.open(`${ingestionApiUrl}/chroma-ui`, "_blank", "noopener,noreferrer")}
          >
            Open Chroma UI
          </button>
        </div>
      </aside>
    </div>
  );
}

export default App;