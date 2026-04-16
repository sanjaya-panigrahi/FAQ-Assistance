import { useEffect, useMemo, useRef, useState } from "react";

const services = [
  { id: "agentic", label: "Agentic RAG", url: "http://localhost:8081/api", framework: "spring-ai" },
  { id: "graph", label: "Graph RAG", url: "http://localhost:8082/api", framework: "spring-ai" },
  { id: "corrective", label: "Corrective RAG", url: "http://localhost:8083/api", framework: "spring-ai" },
  { id: "multimodal", label: "Multimodal RAG", url: "http://localhost:8084/api", framework: "spring-ai" },
  { id: "hierarchical", label: "Hierarchical RAG", url: "http://localhost:8085/api", framework: "spring-ai" },
];

const customerOptions = [
  { value: "mytechstore", label: "mytechstore" },
];

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
  const [question, setQuestion] = useState("What is your laptop return policy?");
  const [imageDescription, setImageDescription] = useState("");
  const [transcript, setTranscript] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [uploadedFaqName, setUploadedFaqName] = useState("");
  const fileInputRef = useRef(null);

  const selectedService = useMemo(
    () => services.find((service) => service.id === selectedServiceId) ?? services[0],
    [selectedServiceId]
  );

  const activeServices = useMemo(() => {
    return [selectedService];
  }, [selectedService]);

  const compareFrameworks = useMemo(
    () => frameworkOptions.map((option) => ({ ...option, ragPattern: selectedService.label })),
    [selectedService]
  );

  const needsImageContext = selectedServiceId === "multimodal";
  const trimmedQuestion = question.trim();
  const hasQuestion = trimmedQuestion.length > 0;
  const hasTranscript = transcript.length > 0;
  const canAsk = !loading && hasQuestion;
  const canExport = !loading && hasTranscript;
  const canStartNewConversation = !loading && (hasTranscript || Boolean(error) || Boolean(uploadedFaqName));
  const canUploadFaq = !loading;
  const primaryActionLabel = loading
    ? mode === "compare"
      ? "Running compare..."
      : "Running query..."
    : mode === "compare"
      ? "Compare all backends"
      : "Run selected backend";

  useEffect(() => {
    if (!error) {
      return;
    }

    setError("");
  }, [question, imageDescription, mode, selectedServiceId, framework, customer]);

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
    if (typeof data.blocked === "boolean") {
      metaEntries.push({ label: "Blocked", value: data.blocked ? "Yes" : "No" });
    }
    if (data.reason) {
      metaEntries.push({ label: "Reason", value: data.reason });
    }
    if (serviceId === "multimodal" && imageDescription.trim()) {
      metaEntries.push({ label: "Image Context", value: imageDescription.trim() });
    }

    return metaEntries;
  }

  async function runQuery(service, prompt) {
    const startedAt = performance.now();
    const payload = service.id === "multimodal"
      ? { question: prompt, imageDescription }
      : { question: prompt };

    try {
      const response = await fetch(`${service.url}/query/ask`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.message || `Request failed with status ${response.status}`);
      }

      return {
        serviceId: service.id,
        serviceLabel: service.label,
        frameworkLabel: frameworkOptions.find((option) => option.value === service.framework)?.label || "Spring AI",
        answer: data.answer || "No answer returned.",
        meta: normalizeMeta(service.id, data),
        latencyMs: Math.round(performance.now() - startedAt),
        status: "success",
      };
    } catch (requestError) {
      return {
        serviceId: service.id,
        serviceLabel: service.label,
        frameworkLabel: frameworkOptions.find((option) => option.value === service.framework)?.label || "Spring AI",
        answer: requestError.message || "Unable to reach backend.",
        meta: [],
        latencyMs: Math.round(performance.now() - startedAt),
        status: "error",
      };
    }
  }

  function createCompareResults(baseResult) {
    return compareFrameworks.map((frameworkOption) => ({
      ...baseResult,
      serviceId: `${baseResult.serviceId}-${frameworkOption.value}`,
      serviceLabel: frameworkOption.label,
      frameworkLabel: frameworkOption.label,
      meta: [
        { label: "RAG Pattern", value: selectedService.label },
        { label: "Customer", value: customer },
        ...baseResult.meta,
      ],
    }));
  }

  async function askQuestion(event) {
    event?.preventDefault();
    if (!hasQuestion || loading) {
      return;
    }

    setLoading(true);
    setError("");

    try {
      const baseResults = await Promise.all(activeServices.map((service) => runQuery(service, trimmedQuestion)));
      const results = mode === "compare" ? createCompareResults(baseResults[0]) : baseResults;
      setTranscript((currentTranscript) => [
        ...currentTranscript,
        {
          id: crypto.randomUUID(),
          question: trimmedQuestion,
          customer,
          framework,
          ragPattern: selectedService.label,
          mode,
          results,
        },
      ]);
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
      return;
    }

    setUploadedFaqName(selectedFile.name);
    setError("FAQ upload UI is ready, but backend file ingestion is not wired yet.");
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
                {customerOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <label className="image-context-field">
            <span>Image Context</span>
            <input
              type="text"
              value={imageDescription}
              onChange={(event) => setImageDescription(event.target.value)}
              disabled={!needsImageContext}
              placeholder={needsImageContext
                ? "Optional description for multimodal comparisons"
                : "Available for compare mode or multimodal RAG"}
            />
          </label>

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
            <button type="button" className="accent-solid" onClick={() => fileInputRef.current?.click()} disabled={!canUploadFaq}>
              Upload FAQ
            </button>
            <input
              ref={fileInputRef}
              className="hidden-input"
              type="file"
              accept=".md,.txt,.json"
              onChange={handleFaqSelection}
            />
          </div>

          {uploadedFaqName && <p className="supporting-note">Selected FAQ file: {uploadedFaqName}</p>}
          {mode === "compare" && (
            <p className="supporting-note">
              Compare mode shows the selected RAG pattern for all frameworks for the selected customer.
            </p>
          )}
          {!needsImageContext && (
            <p className="supporting-note">
              Image context is enabled only for the multimodal RAG pattern.
            </p>
          )}
          {mode !== "compare" && framework !== "spring-ai" && (
            <p className="supporting-note">
              Framework selection is captured in context. Current backend execution is powered by
              Spring AI services.
            </p>
          )}
          {error && <p className="error-banner">{error}</p>}
        </section>

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