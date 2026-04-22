#!/bin/bash
set -e

# FAQ Assistance - Kubernetes Quick Deploy Script
# This script automates building Docker images and deploying to local K8s

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$PROJECT_ROOT/.env"
APP_VERSION="${APP_VERSION:-1.0.0}"

echo "=========================================="
echo "FAQ Assistance - Kubernetes Deployment"
echo "=========================================="
echo ""

# Check prerequisites
echo "Checking prerequisites..."
if ! command -v kubectl &> /dev/null; then
    echo "❌ kubectl not found. Please install kubectl."
    exit 1
fi

if ! command -v docker &> /dev/null; then
    echo "❌ docker not found. Please install Docker."
    exit 1
fi

CONTEXT=$(kubectl config current-context)
echo "✅ kubectl available (context: $CONTEXT)"

read_env_value() {
    local key="$1"
    local env_file="$2"

    if [ ! -f "$env_file" ]; then
        return 1
    fi

    awk -F'=' -v k="$key" '
        $0 ~ "^[[:space:]]*" k "=" {
            v=$0
            sub("^[[:space:]]*" k "=[[:space:]]*", "", v)
            gsub(/^[\"\047]|[\"\047]$/, "", v)
            print v
            exit
        }
    ' "$env_file"
}

ensure_secrets() {
    local openai_key jwt_secret mysql_root_password mysql_password kong_db_password neo4j_password konga_db_password

    openai_key="${OPENAI_API_KEY:-$(read_env_value OPENAI_API_KEY "$ENV_FILE") }"
    openai_key="${openai_key% }"

    jwt_secret="${JWT_SECRET:-$(read_env_value JWT_SECRET "$ENV_FILE") }"
    jwt_secret="${jwt_secret% }"
    if [ -z "$jwt_secret" ]; then
        jwt_secret="$(LC_ALL=C tr -dc 'A-Za-z0-9!@#$%^&*()_+-=' </dev/urandom | head -c 64)"
        echo "⚠️  JWT_SECRET not provided; generated an ephemeral secret for this deployment"
    fi

    mysql_root_password="${MYSQL_ROOT_PASSWORD:-$(read_env_value MYSQL_ROOT_PASSWORD "$ENV_FILE") }"
    mysql_root_password="${mysql_root_password% }"
    if [ -z "$mysql_root_password" ]; then
        mysql_root_password="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)"
        echo "⚠️  MYSQL_ROOT_PASSWORD not provided; generated a random value"
    fi

    mysql_password="${MYSQL_PASSWORD:-$(read_env_value MYSQL_PASSWORD "$ENV_FILE") }"
    mysql_password="${mysql_password% }"
    if [ -z "$mysql_password" ]; then
        mysql_password="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)"
        echo "⚠️  MYSQL_PASSWORD not provided; generated a random value"
    fi

    kong_db_password="${KONG_DB_PASSWORD:-$(read_env_value KONG_DB_PASSWORD "$ENV_FILE") }"
    kong_db_password="${kong_db_password% }"
    if [ -z "$kong_db_password" ]; then
        kong_db_password="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)"
    fi

    neo4j_password="${NEO4J_PASSWORD:-$(read_env_value NEO4J_PASSWORD "$ENV_FILE") }"
    neo4j_password="${neo4j_password% }"
    if [ -z "$neo4j_password" ]; then
        neo4j_password="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)"
        echo "⚠️  NEO4J_PASSWORD not provided; generated a random value"
    fi

    konga_db_password="${KONGA_DB_PASSWORD:-$(read_env_value KONGA_DB_PASSWORD "$ENV_FILE") }"
    konga_db_password="${konga_db_password% }"
    if [ -z "$konga_db_password" ]; then
        konga_db_password="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)"
        echo "⚠️  KONGA_DB_PASSWORD not provided; generated a random value"
    fi

    if [ -z "$openai_key" ]; then
        echo "❌ OPENAI_API_KEY not found."
        echo "Set it in shell env or in $ENV_FILE"
        exit 1
    fi

    kubectl create namespace faq-assistance --dry-run=client -o yaml | kubectl apply -f - >/dev/null

    kubectl -n faq-assistance create secret generic openai-secrets \
      --from-literal=openai-api-key="$openai_key" \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null

        kubectl -n faq-assistance create secret generic auth-secrets \
            --from-literal=jwt-secret="$jwt_secret" \
            --dry-run=client -o yaml | kubectl apply -f - >/dev/null

    kubectl -n faq-assistance create secret generic mysql-secrets \
      --from-literal=mysql-root-password="$mysql_root_password" \
      --from-literal=mysql-password="$mysql_password" \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null

    kubectl -n faq-assistance create secret generic kong-secrets \
      --from-literal=kong-db-password="$kong_db_password" \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null

        kubectl -n faq-assistance create secret generic neo4j-secrets \
            --from-literal=password="neo4j/$neo4j_password" \
            --dry-run=client -o yaml | kubectl apply -f - >/dev/null

        kubectl -n faq-assistance create secret generic konga-secrets \
            --from-literal=postgres-user="konga" \
            --from-literal=postgres-password="$konga_db_password" \
            --from-literal=postgres-db="konga" \
            --from-literal=db-uri="postgresql://konga:$konga_db_password@konga-db:5432/konga" \
            --dry-run=client -o yaml | kubectl apply -f - >/dev/null

    echo "✅ Kubernetes secrets created/updated from app environment"
}

# Parse arguments
BUILD_IMAGES=false
DEPLOY=false
CLEANUP=false
GROUP="all"

print_usage() {
    echo "Usage: $0 [--build] [--deploy] [--all] [--clean] [--group <all|common|agentic|retrieval|rag-pipeline|graph|corrective|multimodal|hierarchical|ingestion>]"
}

deploy_group_resources() {
    local group="$1"

    echo "Applying shared namespace/config/storage/service resources..."
    kubectl apply -f "$SCRIPT_DIR/base/namespaces/namespace.yaml"
    kubectl apply -f "$SCRIPT_DIR/base/configmaps/app-config.yaml"
    kubectl apply -f "$SCRIPT_DIR/base/configmaps/kong-config.yaml"
    kubectl apply -f "$SCRIPT_DIR/base/storage/persistent-volume-claims.yaml"
    kubectl apply -f "$SCRIPT_DIR/base/services/all-services.yaml"

    echo "Applying infrastructure dependencies..."
    kubectl apply -f "$SCRIPT_DIR/base/deployments/01-infrastructure.yaml"

    case "$group" in
        common)
            echo "Applying common support services (Kong, Konga, UI, Analytics)..."
            kubectl apply -f "$SCRIPT_DIR/base/deployments/05-support-services.yaml" -l "app in (kong,konga-db,konga,faq-ui,rag-analytics)"
            ;;
        rag-pipeline|retrieval)
            echo "Applying RAG pipeline services (Spring AI + LangChain + LangGraph retrieval)..."
            kubectl apply -f "$SCRIPT_DIR/base/deployments/02-spring-ai-services.yaml" -l pattern=retrieval
            kubectl apply -f "$SCRIPT_DIR/base/deployments/03-langchain-services.yaml" -l pattern=retrieval
            kubectl apply -f "$SCRIPT_DIR/base/deployments/04-langgraph-services.yaml" -l pattern=retrieval
            ;;
        agentic)
            echo "Applying agentic services (Spring AI + LangChain + LangGraph agentic)..."
            kubectl apply -f "$SCRIPT_DIR/base/deployments/02-spring-ai-services.yaml" -l pattern=agentic
            kubectl apply -f "$SCRIPT_DIR/base/deployments/03-langchain-services.yaml" -l pattern=agentic
            kubectl apply -f "$SCRIPT_DIR/base/deployments/04-langgraph-services.yaml" -l pattern=agentic
            ;;
        graph)
            echo "Applying graph services (Spring AI + LangChain + LangGraph graph)..."
            kubectl apply -f "$SCRIPT_DIR/base/deployments/02-spring-ai-services.yaml" -l pattern=graph
            kubectl apply -f "$SCRIPT_DIR/base/deployments/03-langchain-services.yaml" -l pattern=graph
            kubectl apply -f "$SCRIPT_DIR/base/deployments/04-langgraph-services.yaml" -l pattern=graph
            ;;
        corrective)
            echo "Applying corrective services (Spring AI + LangChain + LangGraph corrective)..."
            kubectl apply -f "$SCRIPT_DIR/base/deployments/02-spring-ai-services.yaml" -l pattern=corrective
            kubectl apply -f "$SCRIPT_DIR/base/deployments/03-langchain-services.yaml" -l pattern=corrective
            kubectl apply -f "$SCRIPT_DIR/base/deployments/04-langgraph-services.yaml" -l pattern=corrective
            ;;
        multimodal)
            echo "Applying multimodal services (Spring AI + LangChain + LangGraph multimodal)..."
            kubectl apply -f "$SCRIPT_DIR/base/deployments/02-spring-ai-services.yaml" -l pattern=multimodal
            kubectl apply -f "$SCRIPT_DIR/base/deployments/03-langchain-services.yaml" -l pattern=multimodal
            kubectl apply -f "$SCRIPT_DIR/base/deployments/04-langgraph-services.yaml" -l pattern=multimodal
            ;;
        hierarchical)
            echo "Applying hierarchical services (Spring AI + LangChain + LangGraph hierarchical)..."
            kubectl apply -f "$SCRIPT_DIR/base/deployments/02-spring-ai-services.yaml" -l pattern=hierarchical
            kubectl apply -f "$SCRIPT_DIR/base/deployments/03-langchain-services.yaml" -l pattern=hierarchical
            kubectl apply -f "$SCRIPT_DIR/base/deployments/04-langgraph-services.yaml" -l pattern=hierarchical
            ;;
        ingestion)
            echo "Applying standalone ingestion service..."
            kubectl apply -f "$SCRIPT_DIR/base/deployments/05-support-services.yaml" -l app=faq-ingestion
            ;;
        *)
            echo "❌ Unsupported group: $group"
            exit 1
            ;;
    esac
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --build)
            BUILD_IMAGES=true
            shift
            ;;
        --deploy)
            DEPLOY=true
            shift
            ;;
        --all)
            BUILD_IMAGES=true
            DEPLOY=true
            shift
            ;;
        --clean)
            CLEANUP=true
            shift
            ;;
        --group)
            if [[ $# -lt 2 ]]; then
                echo "❌ Missing value for --group"
                print_usage
                exit 1
            fi
            GROUP="$2"
            shift 2
            ;;
        *)
            print_usage
            exit 1
            ;;
    esac
done

case "$GROUP" in
    all|common|agentic|retrieval|rag-pipeline|graph|corrective|multimodal|hierarchical|ingestion)
        ;;
    *)
        echo "❌ Invalid --group value: $GROUP"
        print_usage
        exit 1
        ;;
esac

# Build Docker Images
if [ "$BUILD_IMAGES" = true ]; then
    echo ""
    echo "Building Docker images..."
    echo ""
    
    SERVICES=(
        "spring-ai-agentic:faq-assistance:spring-ai-agentic-$APP_VERSION"
        "spring-ai-neo4j-graph:faq-assistance:spring-ai-neo4j-graph-$APP_VERSION"
        "spring-ai-corrective:faq-assistance:spring-ai-corrective-$APP_VERSION"
        "spring-ai-multimodal:faq-assistance:spring-ai-multimodal-$APP_VERSION"
        "spring-ai-hierarchical:faq-assistance:spring-ai-hierarchical-$APP_VERSION"
        "spring-ai-faq-retrieval:faq-assistance:spring-ai-faq-retrieval-$APP_VERSION"
        "langchain-agentic:faq-assistance:langchain-agentic-$APP_VERSION"
        "langchain-neo4j-graph:faq-assistance:langchain-neo4j-graph-$APP_VERSION"
        "langchain-corrective:faq-assistance:langchain-corrective-$APP_VERSION"
        "langchain-multimodal:faq-assistance:langchain-multimodal-$APP_VERSION"
        "langchain-hierarchical:faq-assistance:langchain-hierarchical-$APP_VERSION"
        "langchain-retrieval-service:faq-assistance:langchain-retrieval-service-$APP_VERSION"
        "langgraph-agentic:faq-assistance:langgraph-agentic-$APP_VERSION"
        "langgraph-neo4j-graph:faq-assistance:langgraph-neo4j-graph-$APP_VERSION"
        "langgraph-corrective:faq-assistance:langgraph-corrective-$APP_VERSION"
        "langgraph-multimodal:faq-assistance:langgraph-multimodal-$APP_VERSION"
        "langgraph-hierarchical:faq-assistance:langgraph-hierarchical-$APP_VERSION"
        "langgraph-retrieval-service:faq-assistance:langgraph-retrieval-service-$APP_VERSION"
        "faq-assistance-ui:faq-assistance:faq-ui-$APP_VERSION"
        "rag-analytics:faq-assistance:rag-analytics-$APP_VERSION"
        "spring-ai-faq-ingestion:faq-assistance:faq-ingestion-$APP_VERSION"
    )

    BUILT_IMAGES=()

    for service_spec in "${SERVICES[@]}"; do
        IFS=':' read -r service_dir image_name <<< "$service_spec"
        service_path="$PROJECT_ROOT/$service_dir"

        if [ -d "$service_path" ]; then
            echo "Building $image_name from $service_dir..."
            cd "$service_path"
            build_cmd=(docker build -t "$image_name")
            if [ "$service_dir" = "faq-assistance-ui" ]; then
                build_cmd+=(--build-arg "VITE_API_BASE_URL=http://localhost:8000")
            fi

            if "${build_cmd[@]}" . ; then
                BUILT_IMAGES+=("$image_name")
            else
                echo "⚠️  Warning: build failed for $service_dir, continuing..."
            fi
            cd "$PROJECT_ROOT"
        else
            echo "⚠️  Warning: $service_dir not found, skipping..."
        fi
    done

    echo ""
    echo "✅ Docker images built"
    docker images | grep faq-assistance

    # Import all built images into the Docker Desktop Kubernetes node (kind-based VM).
    # Without this step Kubernetes tries to pull from Docker Hub and fails for local images.
    K8S_NODE="desktop-control-plane"
    if docker inspect "$K8S_NODE" &>/dev/null 2>&1; then
        echo ""
        echo "Importing images into Kubernetes node ($K8S_NODE)..."
        for img in "${BUILT_IMAGES[@]}"; do
            echo "  → $img"
            docker save "$img" | docker exec -i "$K8S_NODE" ctr -n k8s.io images import - >/dev/null
        done
        echo "✅ All images imported into Kubernetes runtime"
    else
        echo "⚠️  Kubernetes node container '$K8S_NODE' not found."
        echo "   If using Docker Desktop, make sure Kubernetes is enabled."
        echo "   If using minikube, run: minikube image load <image-name>"
    fi
fi

# Deploy to Kubernetes
if [ "$DEPLOY" = true ]; then
    echo ""
    echo "Deploying to Kubernetes (group: $GROUP)..."
    echo ""

    ensure_secrets
    
    if [ "$GROUP" = "all" ]; then
        echo "Applying full Kustomize manifests..."
        kubectl apply -k "$SCRIPT_DIR/"
    else
        deploy_group_resources "$GROUP"
    fi
    
    echo ""
    echo "Waiting for deployments to roll out (this may take 2-5 minutes)..."
    deployment_names=$(kubectl get deployments -n faq-assistance -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
    if [ -n "$deployment_names" ]; then
        rollout_failed=false
        for dep in $deployment_names; do
            if ! kubectl rollout status "deployment/$dep" -n faq-assistance --timeout=300s; then
                echo "⚠️  Warning: rollout did not complete for deployment/$dep"
                rollout_failed=true
            fi
        done

        if [ "$rollout_failed" = true ]; then
            echo "⚠️  One or more deployments did not report a successful rollout within timeout"
        fi
    else
        echo "⚠️  No deployments found in namespace faq-assistance"
    fi
    
    echo ""
    echo "✅ Deployment complete!"
    echo ""
    echo "Pod status:"
    kubectl get pods -n faq-assistance
    
    echo ""
    echo "To access services, use port forwarding in separate terminals:"
    echo ""
    echo "  Kong Gateway (API):     kubectl port-forward -n faq-assistance svc/kong-gateway 8000:8000"
    echo "  FAQ UI:                 kubectl port-forward -n faq-assistance svc/faq-ui 5173:5173"
    echo "  Konga Admin:            kubectl port-forward -n faq-assistance svc/konga-ui 1337:1337"
    echo "  Analytics:              kubectl port-forward -n faq-assistance svc/rag-analytics 9191:9191"
    echo ""
    echo "Then access:"
    echo "  - FAQ UI:      http://localhost:5173"
    echo "  - Kong Admin:  http://localhost:1337"
    echo "  - Analytics:   http://localhost:9191"
fi

# Cleanup
if [ "$CLEANUP" = true ]; then
    echo ""
    echo "Cleaning up Kubernetes resources..."
    kubectl delete -k "$SCRIPT_DIR/" --ignore-not-found
    echo "✅ Cleanup complete"
fi

echo ""
echo "Done!"
