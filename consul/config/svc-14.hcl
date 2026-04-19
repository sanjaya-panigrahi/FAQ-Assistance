service {
  name = "langgraph-neo4j-graph"
  id = "langgraph-neo4j-graph"
  address = "langgraph-neo4j-graph"
  port = 8000
  tags = ["python", "langgraph", "graph", "neo4j", "rag"]
  check = {
    id = "langgraph-neo4j-graph-health"
    name = "langgraph-neo4j-graph health"
    http = "http://langgraph-neo4j-graph:8000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

