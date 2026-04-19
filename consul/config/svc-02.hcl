service {
  name = "spring-ai-neo4j-graph"
  id = "spring-ai-neo4j-graph"
  address = "spring-ai-neo4j-graph"
  port = 9000
  tags = ["spring", "graph", "neo4j", "rag"]
  check = {
    id = "spring-ai-neo4j-graph-health"
    name = "spring-ai-neo4j-graph health"
    http = "http://spring-ai-neo4j-graph:9000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

