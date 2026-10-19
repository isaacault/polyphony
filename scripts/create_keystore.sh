#!/usr/bin/env bash
# Create the release signing key and upload it to GitHub Actions secrets.
# Run this once, yourself, so the password never leaves your machine except as a repo secret:
#   scripts/create_keystore.sh
#
# Produces ~/.config/polyphony/release.jks and ~/.config/polyphony/keystore.properties
# (both chmod 600) and sets the secrets KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS,
# KEY_PASSWORD on the GitHub repository (requires `gh auth login`).
#
# BACK UP release.jks AND keystore.properties SOMEWHERE SAFE. Losing them means you can never
# ship an update that installs over existing installs.
set -euo pipefail
DIR="${POLYPHONY_KEY_DIR:-$HOME/.config/polyphony}"
JKS="$DIR/release.jks"
PROPS="$DIR/keystore.properties"
ALIAS="${POLYPHONY_KEY_ALIAS:-polyphony}"
REPO="${POLYPHONY_REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"

command -v keytool >/dev/null || { echo "keytool not found; run: source scripts/env.sh" >&2; exit 1; }
command -v gh >/dev/null || { echo "GitHub CLI (gh) not found" >&2; exit 1; }

mkdir -p "$DIR" && chmod 700 "$DIR"
if [ -f "$JKS" ]; then
  echo "Keystore already exists at $JKS; reusing it."
  PW="$(grep '^storePassword=' "$PROPS" | cut -d= -f2-)"
else
  PW="$(openssl rand -base64 60 | tr -dc 'A-Za-z0-9' | cut -c1-40)"
  keytool -genkeypair -keystore "$JKS" -storetype PKCS12 -alias "$ALIAS" -keyalg RSA -keysize 4096 \
    -validity 10000 -storepass "$PW" -keypass "$PW" \
    -dname "CN=Polyphony, OU=Release, O=${POLYPHONY_KEY_ORG:-Polyphony}, C=US" >/dev/null
  printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' "$JKS" "$PW" "$ALIAS" "$PW" > "$PROPS"
  chmod 600 "$JKS" "$PROPS"
  echo "Created $JKS"
fi

# Local builds: point the project at the properties file via a symlink (git-ignored).
ln -sf "$PROPS" "$(cd "$(dirname "$0")/.." && pwd)/keystore.properties"

echo "Uploading secrets to $REPO ..."
base64 < "$JKS" | tr -d '\n' | gh secret set KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$PW" | gh secret set KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set KEY_ALIAS --repo "$REPO"
printf '%s' "$PW" | gh secret set KEY_PASSWORD --repo "$REPO"
gh secret list --repo "$REPO"
echo
echo "SHA-256 of the signing certificate (publish this so users can verify builds):"
keytool -list -v -keystore "$JKS" -storepass "$PW" -alias "$ALIAS" | grep -E "SHA256:" | head -1
echo
echo "Done. Back up: $JKS and $PROPS"
