#!/usr/bin/env bash
set -euo pipefail

: "${GH_REPO:?GH_REPO is required}"
: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${GITHUB_SERVER_URL:?GITHUB_SERVER_URL is required}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID is required}"

# An old workflow rerun must not roll the rolling release back.
main_sha=$(gh api "repos/$GH_REPO/git/ref/heads/main" --jq '.object.sha')
if [[ "$main_sha" != "$GITHUB_SHA" ]]; then
  echo "Skipping release: this run no longer points to the main branch head."
  exit 0
fi

cd build/ci-artifacts
sha256sum --check SHA256SUMS

# List operations distinguish a missing release/tag from an API or permission error.
# Do not hide network failures behind `|| true` and then delete working releases.
release_id=$(gh api --paginate "repos/$GH_REPO/releases" \
  --jq '.[] | select(.tag_name == "dev-build") | .id')
tag_ref=$(gh api "repos/$GH_REPO/git/matching-refs/tags/dev-build" \
  --jq '.[] | select(.ref == "refs/tags/dev-build") | .ref')

# Resolve the previous release before moving the rolling tag. The commits endpoint
# also dereferences annotated tags, so the comparison always uses immutable SHAs.
previous_sha=''
if [[ -n "$release_id" && -n "$tag_ref" ]]; then
  previous_sha=$(gh api "repos/$GH_REPO/commits/dev-build" --jq '.sha')
fi

if [[ "$previous_sha" == "$GITHUB_SHA" ]]; then
  # Re-running the same release should not replace its changelog with an empty diff.
  gh release view dev-build --json body --jq '.body' > release-notes.md
else
  cat > release-notes.md <<EOF
Automated development build from main. This pre-release is updated in place.

- Commit: $GITHUB_SERVER_URL/$GH_REPO/commit/$GITHUB_SHA
- Build: $GITHUB_SERVER_URL/$GH_REPO/actions/runs/$GITHUB_RUN_ID
- Built at: $(date -u +'%Y-%m-%dT%H:%M:%SZ')

Download XhRec-all.jar and extension.tar. SHA256SUMS contains their SHA-256 checksums.

## Changes
EOF

  if [[ -n "$previous_sha" ]]; then
    gh api --paginate "repos/$GH_REPO/compare/$previous_sha...$GITHUB_SHA?per_page=100" \
      --jq '.commits[] | "- \(.commit.message | split("\n")[0]) ([\(.sha[0:7])](\(.html_url)))"' \
      >> release-notes.md
    printf '\n[Full changelog](%s/%s/compare/%s...%s)\n' \
      "$GITHUB_SERVER_URL" "$GH_REPO" "$previous_sha" "$GITHUB_SHA" >> release-notes.md
  else
    # On the first release there is no previous dev-build to compare against.
    gh api --method POST "repos/$GH_REPO/releases/generate-notes" \
      -f tag_name=dev-build -f target_commitish="$GITHUB_SHA" --jq '.body' \
      >> release-notes.md
  fi
fi

if [[ -n "$release_id" ]]; then
  # Keep the release URL and ID. Replace only these assets after the build passes.
  gh release upload dev-build XhRec-all.jar extension.tar SHA256SUMS --clobber
fi

if [[ -n "$tag_ref" ]]; then
  gh api --method PATCH "repos/$GH_REPO/git/refs/tags/dev-build" \
    -f sha="$GITHUB_SHA" -F force=true --silent
else
  gh api --method POST "repos/$GH_REPO/git/refs" \
    -f ref=refs/tags/dev-build -f sha="$GITHUB_SHA" --silent
fi

if [[ -n "$release_id" ]]; then
  gh release edit dev-build --title 'CI builds' --prerelease --latest=false \
    --notes-file release-notes.md
else
  gh release create dev-build XhRec-all.jar extension.tar SHA256SUMS \
    --verify-tag --title 'CI builds' --prerelease --latest=false \
    --notes-file release-notes.md
fi
