#!/usr/bin/env bash
set -euo pipefail

: "${GH_REPO:?GH_REPO is required}"
: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${GITHUB_SERVER_URL:?GITHUB_SERVER_URL is required}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID is required}"

# The release body is split in two by this marker.
#
#   above it — the introduction: what the artifacts are and what each component changed. It is
#              written by hand (the component sections start out empty on purpose) and every later
#              build carries it over untouched.
#   below it — build metadata and the changelog, regenerated from scratch on every build.
#
# Keep the line byte-identical: it is how the previous body is recognised. If it goes missing, the
# next build falls back to the empty introduction.
readonly NOTES_MARKER='<!-- release-notes: kept by hand above, regenerated below -->'

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

# The introduction used when there is nothing to carry over yet.
#
# The two component sections are deliberately left empty: the job lists commits, it cannot say what
# they mean for a reader, and a guessed sentence in a release is worse than a blank one.
default_introduction() {
  cat <<EOF
# XhRec dev-build

Development build of \`main\`, updated in place. Tagged releases are cut from these builds; see the
[releases page]($GITHUB_SERVER_URL/$GH_REPO/releases).

## What's in the box

| File | What it is |
|---|---|
| \`XhRec-all.jar\` | The recorder: room list, status-driven recording, cuts on disk, WebUI, Prometheus metrics and post-processing. |
| \`XhCut-all.jar\` | The cutter: remote lossless rough-cutting for those recordings, with every ffmpeg computation on the recording host. |
| \`extension.tar\` | Browser extension *XhRec Quick Send* (MV3 + userscript). |
| \`SHA256SUMS\` | SHA-256 checksums for the three artifacts above. |

## XhRec

<!-- What changed in the recorder. Kept as written by every later build. -->

## XhCut

<!-- What changed in the cutter. Kept as written by every later build. -->

EOF
}

if [[ "$previous_sha" == "$GITHUB_SHA" ]]; then
  # Re-running the same build must not replace its changelog with an empty diff, nor discard the
  # introduction that was written by hand in the meantime.
  gh release view dev-build --json body --jq '.body' > release-notes.md
else
  # Carry the hand-written half over from the release being replaced.
  carried=''
  if [[ -n "$release_id" ]]; then
    carried=$(gh release view dev-build --json body --jq '.body')
  fi
  if [[ "$carried" == *"$NOTES_MARKER"* ]]; then
    printf '%s\n' "$carried" | awk -v marker="$NOTES_MARKER" '$0 == marker { exit } { print }' > release-notes.md
  else
    default_introduction > release-notes.md
  fi

  printf '%s\n\n## Build\n\n- Commit: %s/%s/commit/%s\n- Build: %s/%s/actions/runs/%s\n- Built at: %s\n' \
    "$NOTES_MARKER" \
    "$GITHUB_SERVER_URL" "$GH_REPO" "$GITHUB_SHA" \
    "$GITHUB_SERVER_URL" "$GH_REPO" "$GITHUB_RUN_ID" \
    "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" >> release-notes.md

  if [[ -n "$previous_sha" ]]; then
    # The compare endpoint returns bare commits, so the section heading is ours.
    printf "\n## What's Changed\n" >> release-notes.md
    gh api --paginate "repos/$GH_REPO/compare/$previous_sha...$GITHUB_SHA?per_page=100" \
      --jq '.commits[] | "- \(.commit.message | split("\n")[0]) ([\(.sha[0:7])](\(.html_url)))"' \
      >> release-notes.md
    printf '\n[Full changelog](%s/%s/compare/%s...%s)\n' \
      "$GITHUB_SERVER_URL" "$GH_REPO" "$previous_sha" "$GITHUB_SHA" >> release-notes.md
  else
    # No previous dev-build to compare against: let GitHub list the merged pull requests, which
    # already arrive under their own "What's Changed" heading.
    printf '\n' >> release-notes.md
    gh api --method POST "repos/$GH_REPO/releases/generate-notes" \
      -f tag_name=dev-build -f target_commitish="$GITHUB_SHA" --jq '.body' \
      >> release-notes.md
  fi
fi

if [[ -n "$release_id" ]]; then
  # Keep the release URL and ID. Replace only these assets after the build passes.
  gh release upload dev-build XhRec-all.jar XhCut-all.jar extension.tar SHA256SUMS --clobber
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
  gh release create dev-build XhRec-all.jar XhCut-all.jar extension.tar SHA256SUMS \
    --verify-tag --title 'CI builds' --prerelease --latest=false \
    --notes-file release-notes.md
fi
