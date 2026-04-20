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

# Quickstart should boot a usable UI by default, which requires Kong routing.
has_kong_flag="false"
for arg in "$@"; do
    if [ "$arg" = "--with-kong" ] || [ "$arg" = "--with-kong-consul" ]; then
        has_kong_flag="true"
        break
    fi
done

if [ "$has_kong_flag" = "false" ]; then
    exec bash "${RUNNER}" --with-kong "$@"
else
    exec bash "${RUNNER}" "$@"
fi
