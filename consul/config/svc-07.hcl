service {
  name = "faq-ingestion"
  id = "faq-ingestion"
  address = "faq-ingestion"
  port = 9000
  tags = ["spring", "ingestion", "multitenant"]
  check = {
    id = "faq-ingestion-health"
    name = "faq-ingestion health"
    http = "http://faq-ingestion:9000/api/faq-ingestion/health"
    interval = "15s"
    timeout = "3s"
  }
}

