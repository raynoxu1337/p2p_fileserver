#!/usr/bin/env bash
# Rebuilds web/p2p_resolver/resolver.js from web/src (needs Node.js 20+).
# The built file is committed, so you only need this after changing web/src.
set -euo pipefail
cd "$(dirname "$0")/../web"
npm ci --no-audit --no-fund
npm run build
echo "Upload the folder web/p2p_resolver/ to your static host."
