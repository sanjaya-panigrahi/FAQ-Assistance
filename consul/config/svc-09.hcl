service {
  name = "langchain-neo4j-graph"
  id = "langchain-neo4j-graph"
  address = "langchain-neo4j-graph"
  port = 8000
  tags = ["python", "langchain", "graph", "neo4j", "rag"]
  check = {
    id = "langchain-neo4j-graph-health"
    name = "langchain-neo4j-graph health"
    http = "http://langchain-neo4j-graph:8000/actuator/health"
    interval = "15s"
    timeout = "3s"
  }
}

