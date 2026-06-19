#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

docker compose -f docker/diffbot-mcp/compose.yml up -d --build --force-recreate diffbot-mcp
docker compose -f docker/diffbot-mcp/compose.yml ps diffbot-mcp
docker compose -f docker/diffbot-mcp/compose.yml logs --tail=100 diffbot-mcp
