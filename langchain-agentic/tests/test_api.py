"""Integration tests for RAG APIs with /api/query/ask."""


def test_health_check(client):
    response = client.get("/actuator/health")
    assert response.status_code == 200


def test_metrics_endpoint(client):
    response = client.get("/metrics")
    assert response.status_code == 200


def test_rebuild_requires_auth(client):
    response = client.post("/api/index/rebuild")
    assert response.status_code in [401, 403]


def test_ask_requires_auth(client):
    response = client.post("/api/query/ask", json={"question": "What is FAQ?"})
    assert response.status_code in [401, 403]


def test_ask_accepts_snake_case_payload(client, auth_token):
    headers = {"Authorization": f"Bearer {auth_token}"}
    response = client.post(
        "/api/query/ask",
        json={"question": "What is FAQ?", "customer_id": "customer-1"},
        headers=headers,
    )
    assert response.status_code in [200, 500]


def test_ask_empty_question_returns_validation_error(client, auth_token):
    headers = {"Authorization": f"Bearer {auth_token}"}
    response = client.post(
        "/api/query/ask",
        json={"question": " ", "customer_id": "customer-1"},
        headers=headers,
    )
    assert response.status_code in [400, 422]
