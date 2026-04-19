service {
  name = "langchain-multimodal"
  id = "langchain-multimodal"
  address = "langchain-multimodal"
  port = 8000
  tags = ["python", "langchain", "multimodal", "rag"]
  check = {
    id = "langchain-multimodal-health"
    name = "langchain-multimodal health"
    http = "http://langchain-multimodal:8000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

