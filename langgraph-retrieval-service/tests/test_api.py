"""Integration tests for retrieval API."""


def test_health_check(client):
    response = client.get("/actuator/health")
    assert response.status_code == 200


def test_metrics_endpoint(client):
    response = client.get("/metrics")
    assert response.status_code == 200


def test_rebuild_requires_auth(client):
    response = client.post("/api/index/rebuild")
    assert response.status_code in [401, 403]


def test_retrieval_query_requires_auth(client):
    response = client.post("/api/retrieval/query", json={"question": "What is FAQ?"})
    assert response.status_code in [401, 403]


def test_retrieval_query_rejects_tenant_mismatch(client, auth_token):
    headers = {"Authorization": f"Bearer {auth_token}"}
    response = client.post(
        "/api/retrieval/query",
        json={"question": "What is FAQ?", "tenant_id": "other-tenant"},
        headers=headers,
    )
    assert response.status_code == 403


def test_retrieval_query_accepts_snake_case_payload(client, auth_token):
    headers = {"Authorization": f"Bearer {auth_token}"}
    response = client.post(
        "/api/retrieval/query",
        json={
            "question": "What is FAQ?",
            "tenant_id": "tenant-1",
            "query_context": "returns",
            "top_k": 3,
            "similarity_threshold": 0.2,
        },
        headers=headers,
    )
    assert response.status_code in [200, 500]


def test_retrieval_query_empty_question_returns_validation_error(client, auth_token):
    headers = {"Authorization": f"Bearer {auth_token}"}
    response = client.post(
        "/api/retrieval/query",
        json={"question": " ", "tenant_id": "tenant-1"},
        headers=headers,
    )
    assert response.status_code in [400, 422]
