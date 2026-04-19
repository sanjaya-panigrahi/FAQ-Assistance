service {
  name = "langchain-hierarchical"
  id = "langchain-hierarchical"
  address = "langchain-hierarchical"
  port = 8000
  tags = ["python", "langchain", "hierarchical", "rag"]
  check = {
    id = "langchain-hierarchical-health"
    name = "langchain-hierarchical health"
    http = "http://langchain-hierarchical:8000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

