#!/usr/bin/env bash
# scripts/verify.sh — run before every commit.
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/check_forbidden_apis.sh --self-test
./scripts/check_forbidden_apis.sh
./gradlew --console=plain :core:test :replay:build :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
echo "verify.sh: ALL CHECKS PASSED"
