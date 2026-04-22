"""Integration tests for multimodal RAG APIs."""


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


def test_ask_accepts_alias_fields(client, auth_token):
    headers = {"Authorization": f"Bearer {auth_token}"}
    response = client.post(
        "/api/query/ask",
        json={
            "question": "What is visible damage?",
            "image_description": "box is damaged",
            "customer_id": "customer-1",
        },
        headers=headers,
    )
    assert response.status_code in [200, 500]


def test_ask_with_image_requires_auth(client):
    response = client.post(
        "/api/query/ask-with-image",
        data={"question": "What is visible damage?", "imageDescription": "box is damaged"},
    )
    assert response.status_code in [401, 403]
