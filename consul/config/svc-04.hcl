service {
  name = "spring-ai-multimodal"
  id = "spring-ai-multimodal"
  address = "spring-ai-multimodal"
  port = 9000
  tags = ["spring", "multimodal", "rag"]
  check = {
    id = "spring-ai-multimodal-health"
    name = "spring-ai-multimodal health"
    http = "http://spring-ai-multimodal:9000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

