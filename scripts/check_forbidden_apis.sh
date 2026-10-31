#!/usr/bin/env bash
# scripts/check_forbidden_apis.sh — fails if compass/magnetometer APIs appear in source.
set -euo pipefail
cd "$(dirname "$0")/.."
PATTERN='\bTYPE_MAGNETIC_FIELD(_UNCALIBRATED)?\b|\bTYPE_ROTATION_VECTOR\b|\bTYPE_GEOMAGNETIC_ROTATION_VECTOR\b|\bTYPE_ORIENTATION\b|getRotationMatrix[[:space:]]*\(|\bgetOrientation[[:space:]]*\(|getInclination[[:space:]]*\(|\bGeomagneticField\b|android\.hardware\.sensor\.compass'

scan() {  # $@ = directories
  grep -rnE --include='*.kt' --include='*.java' --include='*.xml' --include='*.kts' "$PATTERN" "$@"
}

if [[ "${1:-}" == "--self-test" ]]; then
  tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
  echo 'val s = Sensor.TYPE_ROTATION_VECTOR' > "$tmp/Bad.kt"
  echo 'val s = Sensor.TYPE_GAME_ROTATION_VECTOR; val m = getRotationMatrixFromVector' > "$tmp/Ok.kt"
  hits=$(scan "$tmp" | wc -l)
  if [[ "$hits" -eq 1 ]] && scan "$tmp" | grep -q Bad.kt; then echo "self-test OK"; exit 0; fi
  echo "self-test FAILED (hits=$hits)"; exit 1
fi

dirs=(); for d in app/src core/src replay/src; do [[ -d "$d" ]] && dirs+=("$d"); done
if [[ ${#dirs[@]} -eq 0 ]]; then echo "forbidden-API check: no source dirs yet"; exit 0; fi
if scan "${dirs[@]}"; then
  echo "FAIL: forbidden compass/magnetometer API found (lines above)"; exit 1
fi
echo "OK: no forbidden sensor APIs in ${dirs[*]}"
