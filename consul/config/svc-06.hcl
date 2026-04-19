service {
  name = "spring-ai-faq-retrieval"
  id = "spring-ai-faq-retrieval"
  address = "spring-ai-faq-retrieval"
  port = 9010
  tags = ["spring", "retrieval", "shared-service"]
  check = {
    id = "spring-ai-faq-retrieval-health"
    name = "spring-ai-faq-retrieval health"
    http = "http://spring-ai-faq-retrieval:9010/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

