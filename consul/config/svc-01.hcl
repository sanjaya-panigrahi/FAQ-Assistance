service {
  name = "spring-ai-agentic"
  id = "spring-ai-agentic"
  address = "spring-ai-agentic"
  port = 9000
  tags = ["spring", "agentic", "rag"]
  check = {
    id = "spring-ai-agentic-health"
    name = "spring-ai-agentic health"
    http = "http://spring-ai-agentic:9000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

