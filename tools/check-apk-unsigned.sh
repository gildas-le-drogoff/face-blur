#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
#
# SPDX-License-Identifier: AGPL-3.0-or-later

# Fails unless the given APK is unsigned. Without keystore.properties the
# release build must stay unsigned: F-Droid signs it with its own key.
set -euo pipefail

APK="${1:?usage: $0 <apk>}"
[ -f "$APK" ] || {
	echo "::error::$APK not found."
	exit 1
}

# Several build-tools versions are installed on GitHub runners: use the newest
# apksigner rather than a glob that would expand to several paths.
APKSIGNER="$(ls -d "${ANDROID_HOME:?}"/build-tools/*/apksigner | sort -V | tail -n 1)"
[ -x "$APKSIGNER" ] || {
	echo "::error::apksigner not found in $ANDROID_HOME/build-tools."
	exit 1
}

if OUTPUT="$("$APKSIGNER" verify --print-certs "$APK" 2>&1)"; then
	echo "$OUTPUT"
	echo "::error::$APK is signed — without keystore.properties the release must be unsigned."
	exit 1
fi
# A non-zero exit for another reason (corrupt APK, apksigner crash) must not
# pass for "unsigned".
if ! grep -q "DOES NOT VERIFY" <<< "$OUTPUT"; then
	echo "$OUTPUT"
	echo "::error::apksigner failed unexpectedly on $APK."
	exit 1
fi
echo "$OUTPUT"
echo "✅ $APK is unsigned, as F-Droid expects."
