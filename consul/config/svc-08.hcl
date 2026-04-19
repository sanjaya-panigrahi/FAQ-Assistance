service {
  name = "langchain-agentic"
  id = "langchain-agentic"
  address = "langchain-agentic"
  port = 8000
  tags = ["python", "langchain", "agentic", "rag"]
  check = {
    id = "langchain-agentic-health"
    name = "langchain-agentic health"
    http = "http://langchain-agentic:8000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

