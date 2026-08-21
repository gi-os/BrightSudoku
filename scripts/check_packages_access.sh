#!/usr/bin/env bash
#
# Prove the one dependency this repo does not vendor can actually be downloaded,
# before Gradle spends two minutes configuring in order to find that out.
#
# GitHub Packages serves even a public artifact only to an authenticated caller,
# so a build with no usable credentials dies two hundred lines into a stack trace
# whose last word is "401". That says nothing about which credential is missing,
# nor that secrets on a personal account are per-repository so a new repo
# inherits none of its siblings'. This says it in one line.
#
# Credentials are resolved in the same order settings.gradle.kts uses, and the
# last of the three needs no setup at all: a public package is readable by any
# valid token, and every Actions run is handed one. Lengths are printed, never
# values, which separates a missing credential from a wrong one without putting
# either in the log.
set -uo pipefail

catalog="${1:-gradle/libs.versions.toml}"
repo_url="https://maven.pkg.github.com/lightphone/light-keyboard"

# module = "com.thelightphone.lp3keyboard:ui", version = "0.0.16"
line="$(grep -m1 '^light-keyboard' "$catalog")" || {
  echo "::error::No light-keyboard entry in $catalog. If the SDK's keyboard has been renamed, this check needs renaming with it." >&2
  exit 1
}
module="$(printf '%s' "$line" | cut -d'"' -f2)"
version="$(printf '%s' "$line" | cut -d'"' -f4)"
group_path="$(printf '%s' "${module%%:*}" | tr '.' '/')"
artifact="${module##*:}"
pom="$repo_url/$group_path/$artifact/$version/$artifact-$version.pom"

if [ -n "${GH_PACKAGES_USER:-}" ] && [ -n "${GH_PACKAGES_TOKEN:-}" ]; then
  source_name="the GH_PACKAGES_USER / GH_PACKAGES_TOKEN secrets"
  user="$GH_PACKAGES_USER"
  token="$GH_PACKAGES_TOKEN"
elif [ -n "${GITHUB_ACTOR:-}" ] && [ -n "${GITHUB_TOKEN:-}" ]; then
  source_name="the GITHUB_TOKEN this workflow run was given"
  user="$GITHUB_ACTOR"
  token="$GITHUB_TOKEN"
else
  echo "::error::No GitHub Packages credentials at all. Every Actions run gets a GITHUB_TOKEN, so reaching this means the workflow did not pass one: give the job 'packages: read' and set GITHUB_TOKEN: \${{ secrets.GITHUB_TOKEN }} on this step." >&2
  exit 1
fi

echo "Resolving $module:$version using $source_name (user ${#user} chars, token ${#token} chars)."

status="$(curl -sS -o /dev/null -w '%{http_code}' -u "$user:$token" "$pom")"
case "$status" in
  200)
    echo "ok: $artifact-$version.pom is readable"
    ;;
  401 | 403)
    echo "::error::GitHub Packages refused $source_name with HTTP $status for $module:$version. A token needs the read:packages scope; a fine-grained token needs it too and it is not granted by default. Either fix that token or add GH_PACKAGES_USER and GH_PACKAGES_TOKEN under Settings > Secrets and variables > Actions. gi-os is a personal account rather than an organisation, so those secrets are per-repository and a new repo inherits nothing from its siblings." >&2
    exit 1
    ;;
  404)
    echo "::error::GitHub Packages has no $module:$version at $repo_url (HTTP 404). Either the version in $catalog no longer exists upstream, or the package has been made private and $source_name cannot see it." >&2
    exit 1
    ;;
  *)
    echo "::error::GitHub Packages answered HTTP $status for $module:$version. That is neither an auth failure nor a missing artifact, so it is most likely the registry being unavailable — worth one re-run before looking any further." >&2
    exit 1
    ;;
esac
