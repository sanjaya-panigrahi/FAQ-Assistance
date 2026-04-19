service {
  name = "langchain-corrective"
  id = "langchain-corrective"
  address = "langchain-corrective"
  port = 8000
  tags = ["python", "langchain", "corrective", "rag"]
  check = {
    id = "langchain-corrective-health"
    name = "langchain-corrective health"
    http = "http://langchain-corrective:8000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

