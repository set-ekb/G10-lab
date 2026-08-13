#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
sample="$project_dir/lab-samples/g10_ble_20260812_174109.csv"

result="$({
  awk -F',' '
    $2 == "\"FFF1_TX\"" && $6 == "\"QUERY_CRUISE\"" {
      tx++
      after_query = 1
      next
    }
    after_query && $2 == "\"FFF2_NOTIFY\"" {
      post++
      if ($8 !~ /speed=0/ || $8 !~ /flags18=0x40/) unexpected++
    }
    END {
      printf "%d %d %d", tx + 0, post + 0, unexpected + 0
    }
  ' "$sample"
})"

read -r tx_count post_count unexpected_count <<< "$result"

if [[ "$tx_count" != "1" || "$post_count" != "32" || "$unexpected_count" != "0" ]]; then
  echo "LAB sample regression failed: tx=$tx_count post=$post_count unexpected=$unexpected_count" >&2
  exit 1
fi

echo "LAB sample: OK (1 query, 32 stable post-query FFF2 frames)"
