#!/usr/bin/env bash
# The user's design rules as automatic checks (docs/MACHINE_FIRST_PLAN.md step 3, CLAUDE.md "Design rules").
# Runs every check on every screen render (Mac only, no device) and prints one line per rule:
#   rule → pass / N baselined / N new
# Exit 0 = no new violations, 1 = new violations (listed), 2 = the suite itself broke.
#   --prune   also delete baseline entries that no longer occur (fixed). Never adds entries.
set -uo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

prune=false
[[ "${1:-}" == "--prune" ]] && prune=true

out=app/build/design-rules
base=app/src/test/resources/design-baseline
log=app/build/design-rules.log
rm -rf "$out"
mkdir -p app/build
start=$SECONDS
echo "Design rules: checking every screen (takes a few minutes)…"
./gradlew :app:testDebugUnitTest --tests 'com.kirtanya.reel.design.*' --rerun --console=plain -q >"$log" 2>&1
gradle=$?
secs=$((SECONDS - start))

total_new=0
missing=0
printf '\n%-38s %s\n' "Rule" "Result"
for pair in "touch-areas:48 dp touch areas (phone)" "clipping:No clipping at 360 dp (phone)" "contrast:Text contrast" \
    "tv-focus:TV focus always visible" "hardcoded-colors:Tokens only (no hard-coded colours)"; do
    rule=${pair%%:*}
    title=${pair#*:}
    if ! ls "$out/$rule"/*.txt >/dev/null 2>&1; then
        printf '%-38s %s\n' "$title" "NOT RUN"
        missing=1
        continue
    fi
    checked=$(cat "$out/$rule"/*.txt | awk '/^checked /{s+=$2} END{print s+0}')
    known=$(cat "$out/$rule"/*.txt | grep -c '^known ' || true)
    new=$(cat "$out/$rule"/*.txt | grep -c '^new ' || true)
    skipped=$(cat "$out/$rule"/*.txt | grep -c '^skipped ' || true)
    # Baseline entries not found any more (fixed): a multiset difference on keys (all fields but the last).
    stale=0
    if [[ -f "$base/$rule.txt" ]]; then
        cat "$out/$rule"/*.txt | sed -nE 's/^(known|new) //p' >"$out/$rule.found"
        stale=$(awk -F' [|] ' '
            function key(line,  n, parts, k, i) { n = split(line, parts, / [|] /); k = parts[1]; for (i = 2; i < n; i++) k = k " | " parts[i]; return k }
            FNR == NR { seen[key($0)]++; next }
            /^#/ || NF == 0 { next }
            { k = key($0); if (seen[k] > 0) seen[k]--; else c++ }
            END { print c + 0 }' "$out/$rule.found" "$base/$rule.txt")
        # Only after a clean, complete run: a crashed screen would look "fixed".
        if $prune && [[ $gradle -eq 0 ]] && [[ $stale -gt 0 ]]; then
            awk -F' [|] ' '
                function key(line,  n, parts, k, i) { n = split(line, parts, / [|] /); k = parts[1]; for (i = 2; i < n; i++) k = k " | " parts[i]; return k }
                FNR == NR { seen[key($0)]++; next }
                /^#/ || NF == 0 { print; next }
                { k = key($0); if (seen[k] > 0) { seen[k]--; print } }' "$out/$rule.found" "$base/$rule.txt" >"$base/$rule.txt.tmp" &&
                mv "$base/$rule.txt.tmp" "$base/$rule.txt"
            stale_note=" ($stale fixed, removed from the baseline)"
        else
            stale_note=$([[ $stale -gt 0 ]] && echo " ($stale fixed: run with --prune to drop them)" || echo "")
        fi
    else
        stale_note=""
    fi
    if [[ $new -eq 0 && $known -eq 0 ]]; then result="pass"; else result="$known baselined / $new new"; fi
    [[ $new -eq 0 && $known -gt 0 ]] && result="pass · $known baselined"
    [[ $new -gt 0 ]] && result="FAIL · $known baselined / $new new"
    extra=$([[ $skipped -gt 0 ]] && echo ", $skipped not measurable" || echo "")
    printf '%-38s %s\n' "$title" "$result$stale_note  [$checked checked$extra]"
    total_new=$((total_new + new))
done
printf '\n%d s\n' "$secs"

if [[ $total_new -gt 0 ]]; then
    echo
    echo "New violations (fix them; never add them to a baseline without the user's OK):"
    cat "$out"/*/*.txt | sed -n 's/^new /  /p'
    exit 1
fi
if [[ $gradle -ne 0 || $missing -ne 0 ]]; then
    echo
    echo "The design-rules suite didn't finish cleanly; last lines of $log:"
    tail -30 "$log"
    exit 2
fi
exit 0
