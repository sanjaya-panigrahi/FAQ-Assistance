service {
  name = "langgraph-multimodal"
  id = "langgraph-multimodal"
  address = "langgraph-multimodal"
  port = 8000
  tags = ["python", "langgraph", "multimodal", "rag"]
  check = {
    id = "langgraph-multimodal-health"
    name = "langgraph-multimodal health"
    http = "http://langgraph-multimodal:8000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

