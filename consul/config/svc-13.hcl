service {
  name = "langgraph-agentic"
  id = "langgraph-agentic"
  address = "langgraph-agentic"
  port = 8000
  tags = ["python", "langgraph", "agentic", "rag"]
  check = {
    id = "langgraph-agentic-health"
    name = "langgraph-agentic health"
    http = "http://langgraph-agentic:8000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

