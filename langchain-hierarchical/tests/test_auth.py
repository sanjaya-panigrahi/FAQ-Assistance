"""Authentication endpoint tests."""


def test_login_success(client):
    response = client.post(
        "/auth/login",
        json={"username": "admin", "password": "admin123", "tenant_id": "smoke_tenant"},
    )
    assert response.status_code == 200
    body = response.json()
    assert "access_token" in body
    assert "refresh_token" in body


def test_login_rejects_invalid_credentials(client):
    response = client.post(
        "/auth/login",
        json={"username": "admin", "password": "wrong-password", "tenant_id": "smoke_tenant"},
    )
    assert response.status_code == 401


def test_register_and_refresh(client):
    register_response = client.post(
        "/auth/register",
        json={
            "username": "new-user",
            "password": "secret123",
            "tenant_id": "tenant-9",
            "email": "new-user@example.com",
        },
    )
    assert register_response.status_code == 200

    login_response = client.post(
        "/auth/login",
        json={"username": "new-user", "password": "secret123", "tenant_id": "tenant-9"},
    )
    assert login_response.status_code == 200
    refresh_token = login_response.json()["refresh_token"]

    refresh_response = client.post("/auth/refresh", json={"refresh_token": refresh_token})
    assert refresh_response.status_code == 200
    assert "access_token" in refresh_response.json()
