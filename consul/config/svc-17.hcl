service {
  name = "langgraph-hierarchical"
  id = "langgraph-hierarchical"
  address = "langgraph-hierarchical"
  port = 8000
  tags = ["python", "langgraph", "hierarchical", "rag"]
  check = {
    id = "langgraph-hierarchical-health"
    name = "langgraph-hierarchical health"
    http = "http://langgraph-hierarchical:8000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}
