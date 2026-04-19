service {
  name = "langgraph-corrective"
  id = "langgraph-corrective"
  address = "langgraph-corrective"
  port = 8000
  tags = ["python", "langgraph", "corrective", "rag"]
  check = {
    id = "langgraph-corrective-health"
    name = "langgraph-corrective health"
    http = "http://langgraph-corrective:8000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

