service {
  name = "spring-ai-corrective"
  id = "spring-ai-corrective"
  address = "spring-ai-corrective"
  port = 9000
  tags = ["spring", "corrective", "rag"]
  check = {
    id = "spring-ai-corrective-health"
    name = "spring-ai-corrective health"
    http = "http://spring-ai-corrective:9000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

