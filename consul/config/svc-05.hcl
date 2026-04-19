service {
  name = "spring-ai-hierarchical"
  id = "spring-ai-hierarchical"
  address = "spring-ai-hierarchical"
  port = 9000
  tags = ["spring", "hierarchical", "rag"]
  check = {
    id = "spring-ai-hierarchical-health"
    name = "spring-ai-hierarchical health"
    http = "http://spring-ai-hierarchical:9000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

