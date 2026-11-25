#!/usr/bin/env bash
# .github/scripts/release-port.sh

set -euo pipefail

tag="$PORT/v$VERSION"
repo="$GITHUB_REPOSITORY"
tag_ref="refs/tags/$tag"
workflow="repos/$repo/actions/workflows/publish-$PORT.yml"
summary="$GITHUB_STEP_SUMMARY"

release_exists() {
  local output
  if output=$(gh release view "$tag" --repo "$repo" --json url,isDraft 2>&1); then
    jq -e '.isDraft == false' <<< "$output" >/dev/null
  elif [[ "$output" == *"release not found"* || "$output" == *"HTTP 404"* ]]; then
    return 1
  else
    printf '%s\n' "$output" >&2
    exit 1
  fi
}

registry_url=
case "$PORT" in
  npm) registry_url="https://registry.npmjs.org/jpcl-lang/$VERSION" ;;
  py) registry_url="https://pypi.org/pypi/jpcl/$VERSION/json" ;;
esac

if [[ -n "$registry_url" ]]; then
  status=$(curl --silent --show-error --retry 2 --max-time 30 \
    --output /dev/null --write-out '%{http_code}' "$registry_url")
  case "$status" in
    200)
      # Recover a missing release record after a successful publish.
      if git show-ref --verify --quiet "$tag_ref" && ! release_exists; then
        gh release create "$tag" --repo "$repo" --verify-tag --title "$tag" --generate-notes
      fi
      printf '### %s\n\nAlready published on the registry.\n' "$tag" >> "$summary"
      exit 0
      ;;
    404) ;;
    *)
      echo "::error::Registry version lookup returned HTTP $status for $tag"
      exit 1
      ;;
  esac
elif release_exists; then
  printf '### %s\n\nGitHub release already exists.\n' "$tag" >> "$summary"
  exit 0
fi

if target=$(git rev-parse --verify --quiet "$tag_ref^{commit}"); then
  if [[ "$target" != "$GITHUB_SHA" ]]; then
    echo "::error::$tag already points to $target. Retry its original publisher run or release a new version."
    exit 1
  fi
else
  gh api --method POST "repos/$repo/git/refs" \
    -f ref="$tag_ref" -f sha="$GITHUB_SHA" >/dev/null
fi

# Reuse an active run if the dispatcher was interrupted while waiting.
runs=$(gh api --method GET "$workflow/runs" \
  -f branch="$tag" -f head_sha="$GITHUB_SHA" -F per_page=30)
run_id=$(jq -r '[.workflow_runs[] | select(.status != "completed")][0].id // empty' <<< "$runs")

if [[ -z "$run_id" ]]; then
  run_id=$(gh api --method POST "$workflow/dispatches" \
    -H 'X-GitHub-Api-Version: 2026-03-10' \
    -f ref="$tag" -F return_run_details=true --jq '.workflow_run_id')
fi

if [[ ! "$run_id" =~ ^[0-9]+$ ]]; then
  echo "::error::GitHub did not return a publisher run ID for $tag"
  exit 1
fi

printf '### %s\n\n[Publisher run](%s/%s/actions/runs/%s)\n' \
  "$tag" "$GITHUB_SERVER_URL" "$repo" "$run_id" >> "$summary"

while true; do
  run=$(gh api "repos/$repo/actions/runs/$run_id")
  if [[ "$(jq -r .status <<< "$run")" == "completed" ]]; then
    conclusion=$(jq -r .conclusion <<< "$run")
    if [[ "$conclusion" != "success" ]]; then
      echo "::error::$tag publisher ended with $conclusion. See run $run_id."
      exit 1
    fi
    break
  fi
  sleep 30
done

if [[ -n "$registry_url" ]] && ! release_exists; then
  gh release create "$tag" --repo "$repo" --verify-tag --title "$tag" --generate-notes
fi
printf '\nPublisher succeeded.\n' >> "$summary"
