import { useEffect, useMemo, useRef, useState } from "react";

const kongGatewayUrl = "http://localhost:9080";
const ingestionApiUrl = `${kongGatewayUrl}/spring/ingestion/api/faq-ingestion`;
const analyticsApiUrl = `${kongGatewayUrl}/spring/analytics/api/analytics`;
const fallbackCustomers = [
  { customerId: "mytechstore", name: "mytechstore" },
];

const services = [
  { id: "agentic",    label: "Agentic RAG" },
  { id: "graph",      label: "Graph RAG" },
  { id: "corrective", label: "Corrective RAG" },
  { id: "multimodal", label: "Multimodal RAG" },
  { id: "hierarchical", label: "Hierarchical RAG" },
];

// Base URLs keyed by [framework][serviceId]
const serviceUrls = {
  "spring-ai": {
    agentic:      `${kongGatewayUrl}/spring/agentic/api`,
    graph:        `${kongGatewayUrl}/spring/graph/api`,
    corrective:   `${kongGatewayUrl}/spring/corrective/api`,
    multimodal:   `${kongGatewayUrl}/spring/multimodal/api`,
    hierarchical: `${kongGatewayUrl}/spring/hierarchical/api`,
  },
  langchain: {
    agentic:      `${kongGatewayUrl}/langchain/agentic/api`,
    graph:        `${kongGatewayUrl}/langchain/graph/api`,
    corrective:   `${kongGatewayUrl}/langchain/corrective/api`,
    multimodal:   `${kongGatewayUrl}/langchain/multimodal/api`,
    hierarchical: `${kongGatewayUrl}/langchain/hierarchical/api`,
  },
  langgraph: {
    agentic:      `${kongGatewayUrl}/langgraph/agentic/api`,
    graph:        `${kongGatewayUrl}/langgraph/graph/api`,
    corrective:   `${kongGatewayUrl}/langgraph/corrective/api`,
    multimodal:   `${kongGatewayUrl}/langgraph/multimodal/api`,
    hierarchical: `${kongGatewayUrl}/langgraph/hierarchical/api`,
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
  const [selectedServiceId, setSelectedServiceId] = useState("hierarchical");
  const [framework, setFramework] = useState("spring-ai");
  const [customer, setCustomer] = useState("mytechstore");
  const [customers, setCustomers] = useState(fallbackCustomers);
  const [question, setQuestion] = useState("What is your laptop return policy?");
  const [imageDescription, setImageDescription] = useState("");
  const [uploadedImage, setUploadedImage] = useState(null);
  const [transcript, setTranscript] = useState([]);
  const [loading, setLoading] = useState(false);
  const [uploadingFaq, setUploadingFaq] = useState(false);
  const [customerBusy, setCustomerBusy] = useState(false);
  const [error, setError] = useState("");
  const [uploadedFaqName, setUploadedFaqName] = useState("");
  const [selectedFaqFile, setSelectedFaqFile] = useState(null);
  const [documentManagerOpen, setDocumentManagerOpen] = useState(false);
  const [customerStatus, setCustomerStatus] = useState("Loading customers from the ingestion service...");
  const [faqUploadStatus, setFaqUploadStatus] = useState("");
  const [faqUploadResult, setFaqUploadResult] = useState(null);
  const [customerDocuments, setCustomerDocuments] = useState([]);
  const [documentsLoading, setDocumentsLoading] = useState(false);
  const [documentsStatus, setDocumentsStatus] = useState("");
  const [newCustomerId, setNewCustomerId] = useState("");
  const [newCustomerName, setNewCustomerName] = useState("");
  const [newCustomerDescription, setNewCustomerDescription] = useState("");
  const [dashboardLoading, setDashboardLoading] = useState(false);
  const [dashboardError, setDashboardError] = useState("");
  const [leaderboard, setLeaderboard] = useState([]);
  const [recentRuns, setRecentRuns] = useState([]);
  const [analyticsVisible, setAnalyticsVisible] = useState(true);
  const fileInputRef = useRef(null);
  const imageInputRef = useRef(null);

  const selectedService = useMemo(
    () => services.find((service) => service.id === selectedServiceId) ?? services[0],
    [selectedServiceId]
  );

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

  const bestAverageFramework = useMemo(() => {
    if (leaderboard.length === 0) {
      return null;
    }

    return leaderboard.reduce((bestRow, currentRow) => {
      if (!bestRow) {
        return currentRow;
      }

      if (currentRow.avgEffectiveRagScore > bestRow.avgEffectiveRagScore) {
        return currentRow;
      }

      return bestRow;
    }, null);
  }, [leaderboard]);

  const needsImageContext = selectedServiceId === "multimodal";
  const trimmedQuestion = question.trim();
  const trimmedCustomerId = customer.trim();
  const trimmedNewCustomerId = newCustomerId.trim();
  const trimmedNewCustomerName = newCustomerName.trim();
  const hasQuestion = trimmedQuestion.length > 0;
  const hasTranscript = transcript.length > 0;
  const searchEnabled = selectedServiceId === "agentic" || selectedServiceId === "graph";
  const canAsk = searchEnabled && !loading && !uploadingFaq && hasQuestion;
  const canExport = !loading && hasTranscript;
  const canStartNewConversation = !loading && (hasTranscript || Boolean(error) || Boolean(uploadedFaqName));
  const canUploadFaq = !loading && !uploadingFaq && trimmedCustomerId.length > 0;
  const canCreateCustomer = !customerBusy && trimmedNewCustomerId.length > 0 && trimmedNewCustomerName.length > 0;
  const primaryActionLabel = loading
    ? mode === "compare"
      ? "Running compare..."
      : "Running query..."
    : mode === "compare"
      ? "Compare all backends"
      : "Run selected backend";

  async function parseResponse(response) {
    const contentType = response.headers.get("content-type") || "";

    if (contentType.includes("application/json")) {
      return response.json();
    }

    const text = await response.text();
    return text ? { message: text } : {};
  }

  async function loadCustomers(preferredCustomerId) {
    try {
      const response = await fetch(`${ingestionApiUrl}/customers`);
      const data = await parseResponse(response);

      if (!response.ok) {
        throw new Error(data.message || `Unable to load customers (${response.status})`);
      }

      if (!Array.isArray(data) || data.length === 0) {
        setCustomers(fallbackCustomers);
        setCustomer((currentCustomer) => currentCustomer || fallbackCustomers[0].customerId);
        setCustomerStatus("Ingestion service is reachable, but no customers exist yet. Create one below.");
        return;
      }

      const nextCustomers = data.map((entry) => ({
        customerId: entry.customerId,
        name: entry.name || entry.customerId,
      }));

      setCustomers(nextCustomers);
      setCustomer((currentCustomer) => {
        const targetCustomerId = preferredCustomerId || currentCustomer;
        return nextCustomers.some((entry) => entry.customerId === targetCustomerId)
          ? targetCustomerId
          : nextCustomers[0].customerId;
      });
      setCustomerStatus(`Loaded ${nextCustomers.length} customer${nextCustomers.length === 1 ? "" : "s"} from the ingestion service.`);
    } catch (requestError) {
      setCustomers(fallbackCustomers);
      setCustomerStatus(requestError.message || "Could not reach the ingestion service. Using the local fallback customer list.");
    }
  }

  async function loadCustomerDocuments(customerIdToLoad = trimmedCustomerId) {
    if (!customerIdToLoad) {
      setCustomerDocuments([]);
      setDocumentsStatus("Select a customer to inspect indexed FAQ documents.");
      return;
    }

    setDocumentsLoading(true);

    try {
      const response = await fetch(`${ingestionApiUrl}/customers/${customerIdToLoad}/documents`);
      const data = await parseResponse(response);

      if (!response.ok) {
        throw new Error(data.message || `Unable to load documents (${response.status})`);
      }

      const documents = Array.isArray(data) ? data : [];
      setCustomerDocuments(documents);
      setDocumentsStatus(
        documents.length === 0
          ? `No indexed FAQ documents found for ${customerIdToLoad}.`
          : `Loaded ${documents.length} document${documents.length === 1 ? "" : "s"} for ${customerIdToLoad}.`
      );
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

    loadCustomerDocuments(customer);
  }, [documentManagerOpen, customer]);

  useEffect(() => {
    loadAnalyticsDashboard();
  }, [selectedService.label, customer, transcript.length]);

  function extractMetaValue(meta, label) {
    return meta.find((entry) => entry.label === label)?.value;
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
      const queryParams = new URLSearchParams({
        ragPattern: selectedService.label,
        customer,
        limit: "12",
      });

      const response = await fetch(`${analyticsApiUrl}/dashboard?${queryParams.toString()}`);
      const data = await parseResponse(response);

      if (!response.ok) {
        throw new Error(data.message || `Unable to load analytics (${response.status})`);
      }

      setLeaderboard(Array.isArray(data.leaderboard) ? data.leaderboard : []);
      setRecentRuns(Array.isArray(data.recent) ? data.recent : []);
    } catch (requestError) {
      setLeaderboard([]);
      setRecentRuns([]);
      setDashboardError(requestError.message || "Analytics dashboard is currently unavailable.");
    } finally {
      setDashboardLoading(false);
    }
  }

  function normalizeMeta(serviceId, data) {
    const metaEntries = [];

    if (typeof data.chunksUsed === "number") {
      metaEntries.push({ label: "Chunks Used", value: data.chunksUsed });
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
        const payload = service.id === "multimodal"
          ? { question: prompt, customerId: trimmedCustomerId, imageDescription }
          : { question: prompt, customerId: trimmedCustomerId };

        response = await fetch(`${baseUrl}/query/ask`, {
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
            const payload = selectedService.id === "multimodal"
              ? { question: prompt, customerId: trimmedCustomerId, imageDescription }
              : { question: prompt, customerId: trimmedCustomerId };

            response = await fetch(`${fw.url}/query/ask`, {
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
      const results = mode === "compare"
        ? await runCompare(trimmedQuestion)
        : await Promise.all(activeServices.map((service) => runQuery(service, trimmedQuestion)));
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
    setSelectedFaqFile(null);
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

  function handleFaqSelection(event) {
    const selectedFile = event.target.files?.[0];
    if (!selectedFile) {
      setSelectedFaqFile(null);
      setUploadedFaqName("");
      return;
    }

    const allowedExtensions = ["pdf", "md", "yaml", "yml", "doc", "docx", "txt", "png", "jpg", "jpeg"];
    const fileExtension = selectedFile.name.split(".").pop()?.toLowerCase();

    if (!fileExtension || !allowedExtensions.includes(fileExtension)) {
      setError("Unsupported FAQ file type. Use PDF, Markdown, YAML, DOC, DOCX, TXT, PNG, JPG, or JPEG.");
      setSelectedFaqFile(null);
      setUploadedFaqName("");
      event.target.value = "";
      return;
    }

    setError("");
    setFaqUploadStatus("");
    setFaqUploadResult(null);
    setSelectedFaqFile(selectedFile);
    setUploadedFaqName(selectedFile.name);
  }

  async function handleFaqUpload() {
    if (!selectedFaqFile || !trimmedCustomerId || uploadingFaq) {
      return;
    }

    setUploadingFaq(true);
    setError("");
    setFaqUploadStatus(`Uploading ${selectedFaqFile.name} for ${trimmedCustomerId}...`);

    try {
      const formData = new FormData();
      formData.append("customerId", trimmedCustomerId);
      formData.append("file", selectedFaqFile);

      const response = await fetch(`${ingestionApiUrl}/documents/upload`, {
        method: "POST",
        body: formData,
      });
      const data = await parseResponse(response);

      if (!response.ok) {
        throw new Error(data.message || data.processingError || `Upload failed with status ${response.status}`);
      }

      setFaqUploadResult(data);
      setFaqUploadStatus(`Indexed ${data.originalFileName || selectedFaqFile.name} for ${trimmedCustomerId}.`);
      setUploadedFaqName(data.originalFileName || selectedFaqFile.name);
      await loadCustomerDocuments(trimmedCustomerId);
      setSelectedFaqFile(null);
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
    } catch (requestError) {
      setFaqUploadResult(null);
      setError(requestError.message || "Failed to upload FAQ document.");
      setFaqUploadStatus("");
    } finally {
      setUploadingFaq(false);
    }
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

  return (
    <div className="app-shell">
      <main className="workspace">
        <section className="hero-card">
          <h1>FAQ Assistant</h1>
          <p className="hero-copy">
            Choose Mode + RAG pattern + Framework + Customer, then ask your query.
          </p>
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
                  <option key={service.id} value={service.id}>
                    {service.label}
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
              Start New Conversation
            </button>
            <button type="button" className="accent-solid" onClick={exportTranscript} disabled={!canExport}>
              Export Transcript
            </button>
            <button type="button" className="accent-soft" onClick={() => setDocumentManagerOpen((currentValue) => !currentValue)}>
              {documentManagerOpen ? "Hide document tools" : "Manage FAQ documents"}
            </button>
            <button type="button" className="accent-soft" onClick={() => setAnalyticsVisible((currentValue) => !currentValue)}>
              {analyticsVisible ? "Hide analytics" : "Show analytics"}
            </button>
          </div>

          <p className="supporting-note">{customerStatus}</p>
          {!searchEnabled && (
            <p className="supporting-note">
              Search is temporarily enabled only for Agentic RAG and Graph RAG.
            </p>
          )}
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
              API requests are routed through Kong gateway on port 9080 ({framework === "spring-ai" ? "/spring/*" : framework === "langchain" ? "/langchain/*" : "/langgraph/*"}).
            </p>
          )}
          {error && <p className="error-banner">{error}</p>}

          {documentManagerOpen && (
            <section className="document-manager-card">
              <div className="document-manager-header">
                <div>
                  <p className="section-label">Document Management</p>
                  <h2>Ingestion Controls</h2>
                </div>
                <button type="button" className="accent-soft" onClick={() => window.open(`${ingestionApiUrl}/chroma-ui`, "_blank", "noopener,noreferrer")}>
                  Open Chroma UI
                </button>
              </div>

              <div className="document-manager-grid">
                <form className="manager-panel" onSubmit={handleCreateCustomer}>
                  <p className="meta-title">Create Customer</p>
                  <label>
                    <span>Customer ID</span>
                    <input
                      type="text"
                      value={newCustomerId}
                      onChange={(event) => setNewCustomerId(event.target.value)}
                      placeholder="acme_corp"
                    />
                  </label>
                  <label>
                    <span>Display Name</span>
                    <input
                      type="text"
                      value={newCustomerName}
                      onChange={(event) => setNewCustomerName(event.target.value)}
                      placeholder="ACME Corp"
                    />
                  </label>
                  <label>
                    <span>Description (optional)</span>
                    <input
                      type="text"
                      value={newCustomerDescription}
                      onChange={(event) => setNewCustomerDescription(event.target.value)}
                      placeholder="Customer-specific FAQ knowledge base"
                    />
                  </label>
                  <div className="toolbar-actions compact-actions">
                    <button type="submit" className="accent-solid" disabled={!canCreateCustomer}>
                      {customerBusy ? "Creating..." : "Create customer"}
                    </button>
                    <button type="button" className="accent-soft" onClick={() => loadCustomers(customer)} disabled={customerBusy}>
                      Refresh list
                    </button>
                  </div>
                </form>
              </div>

              <div className="manager-panel document-history-panel">
                <div className="document-history-header">
                  <p className="meta-title">Indexed Documents</p>
                  <button type="button" className="accent-soft" onClick={() => loadCustomerDocuments(customer)} disabled={documentsLoading}>
                    {documentsLoading ? "Refreshing..." : "Refresh documents"}
                  </button>
                </div>
                <p className="supporting-note">{documentsStatus || `Viewing indexed documents for ${trimmedCustomerId}.`}</p>

                {customerDocuments.length > 0 ? (
                  <div className="document-history-list">
                    {customerDocuments.map((document) => (
                      <article key={document.id} className="document-history-item">
                        <div>
                          <h3>{document.originalFileName}</h3>
                          <p className="supporting-note">{document.fileType?.toUpperCase()} | {document.processingStatus}</p>
                        </div>
                        <ul>
                          <li><strong>Structure:</strong> {document.detectedStructure || "Pending"}</li>
                          <li><strong>Chunks:</strong> {document.indexedChunkCount ?? document.chunkCount ?? 0}</li>
                          <li><strong>Size:</strong> {Math.round((document.fileSizeBytes || 0) / 1024)} KB</li>
                        </ul>
                      </article>
                    ))}
                  </div>
                ) : (
                  <div className="upload-summary">
                    <p>No indexed documents to show yet.</p>
                  </div>
                )}
              </div>
            </section>
          )}
        </section>

        {analyticsVisible && (
        <section className="dashboard-card">
          <div className="dashboard-header">
            <div>
              <p className="section-label">Analytics Dashboard</p>
              <h2>Framework Performance ({selectedService.label})</h2>
            </div>
            <button type="button" className="accent-soft" onClick={loadAnalyticsDashboard} disabled={dashboardLoading}>
              {dashboardLoading ? "Refreshing..." : "Refresh analytics"}
            </button>
          </div>

          {dashboardError && <p className="error-banner">{dashboardError}</p>}

          <div className="analytics-grid">
            <div className="manager-panel">
              <p className="meta-title">Leaderboard</p>
              {leaderboard.length === 0 ? (
                <p className="supporting-note">No analytics runs available yet for this customer + pattern.</p>
              ) : (
                <div className="table-scroll">
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
                      {leaderboard.map((row) => {
                        const isBestAverage = bestAverageFramework && row.framework === bestAverageFramework.framework;

                        return (
                        <tr key={`${row.framework}-${row.ragPattern}`} className={isBestAverage ? "best-framework-row" : ""}>
                          <td>{row.framework}</td>
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
              {recentRuns.length === 0 ? (
                <p className="supporting-note">No recent runs captured yet.</p>
              ) : (
                <div className="table-scroll">
                  <table className="analytics-table">
                    <thead>
                      <tr>
                        <th>Framework</th>
                        <th>Status</th>
                        <th>Latency</th>
                        <th>Score</th>
                        <th>Strategy</th>
                      </tr>
                    </thead>
                    <tbody>
                      {recentRuns.map((row, index) => (
                        <tr key={`${row.framework}-${row.createdAt}-${index}`}>
                          <td>{row.framework}</td>
                          <td>{row.status}</td>
                          <td>{row.latencyMs} ms</td>
                          <td>{Number(row.effectiveRagScore).toFixed(3)}</td>
                          <td>{row.strategy || "-"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>
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
                      <p className="result-answer">{result.answer}</p>
                      <p className="result-latency">Latency: {result.latencyMs} ms</p>
                      {result.meta.length > 0 && (
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
    </div>
  );
}

export default App;