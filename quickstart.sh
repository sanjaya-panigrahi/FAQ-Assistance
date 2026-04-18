#!/bin/bash

# FAQ Assistance - Quick Start Script
# Wrapper around run-all-stacks.sh to keep one startup implementation.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="${SCRIPT_DIR}/run-all-stacks.sh"

if [ ! -f "${RUNNER}" ]; then
    echo "run-all-stacks.sh not found next to quickstart.sh"
    exit 1
fi

exec bash "${RUNNER}"
