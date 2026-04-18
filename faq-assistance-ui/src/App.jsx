import { useEffect, useMemo, useRef, useState } from "react";

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
    agentic:      "http://localhost:8081/api",
    graph:        "http://localhost:8082/api",
    corrective:   "http://localhost:8083/api",
    multimodal:   "http://localhost:8084/api",
    hierarchical: "http://localhost:8085/api",
  },
  langchain: {
    agentic:      "http://localhost:8181/api",
    graph:        "http://localhost:8182/api",
    corrective:   "http://localhost:8183/api",
    multimodal:   "http://localhost:8184/api",
    hierarchical: "http://localhost:8185/api",
  },
  langgraph: {
    agentic:      "http://localhost:8281/api",
    graph:        "http://localhost:8282/api",
    corrective:   "http://localhost:8283/api",
    multimodal:   "http://localhost:8284/api",
    hierarchical: "http://localhost:8285/api",
  },
};

function resolveUrl(frameworkValue, serviceId) {
  return serviceUrls[frameworkValue]?.[serviceId] ?? serviceUrls["spring-ai"][serviceId];
}

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
  const [uploadedImage, setUploadedImage] = useState(null);
  const [transcript, setTranscript] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [uploadedFaqName, setUploadedFaqName] = useState("");
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
        formData.append("imageDescription", imageDescription);
        formData.append("image", uploadedImage);

        response = await fetch(`${baseUrl}/query/ask-with-image`, {
          method: "POST",
          body: formData,
        });
      } else {
        const payload = service.id === "multimodal"
          ? { question: prompt, imageDescription }
          : { question: prompt };

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
            formData.append("imageDescription", imageDescription);
            formData.append("image", uploadedImage);

            response = await fetch(`${fw.url}/query/ask-with-image`, {
              method: "POST",
              body: formData,
            });
          } else {
            const payload = selectedService.id === "multimodal"
              ? { question: prompt, imageDescription }
              : { question: prompt };

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
      const results = mode === "compare"
        ? await runCompare(trimmedQuestion)
        : await Promise.all(activeServices.map((service) => runQuery(service, trimmedQuestion)));
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
    setUploadedImage(null);
    if (imageInputRef.current) {
      imageInputRef.current.value = "";
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
      return;
    }

    setUploadedFaqName(selectedFile.name);
    setError("FAQ upload UI is ready, but backend file ingestion is not wired yet.");
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
                {customerOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
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
          {needsImageContext && uploadedImage && (
            <p className="supporting-note">
              Selected image: <strong>{uploadedImage.name}</strong> ({Math.round(uploadedImage.size / 1024)} KB)
            </p>
          )}
          {mode !== "compare" && (
            <p className="supporting-note">
              Framework is set to <strong>{frameworkOptions.find((f) => f.value === framework)?.label}</strong>. 
              API requests are routed to the corresponding backend ({framework === "spring-ai" ? "ports 8081-8085" : framework === "langchain" ? "ports 8181-8185" : "ports 8281-8285"}).
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