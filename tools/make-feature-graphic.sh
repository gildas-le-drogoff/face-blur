#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
#
# SPDX-License-Identifier: AGPL-3.0-or-later

# Generates the 1024x500 store banner (featureGraphic.png) of each locale:
# the launcher icon on the left, the title and short description on the right,
# over the icon background colour. Requires ImageMagick 7 and the Roboto fonts.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
META="$ROOT/fastlane/metadata/android"
ICON="$META/en-US/images/icon.png"
BG="#283593"
FONT_DIR="${FONT_DIR:-/usr/share/fonts/TTF}"

for dir in "$META"/*/; do
	dir="${dir%/}"
	locale="$(basename "$dir")"
	title="$(< "$dir/title.txt")"
	# Break the short description after its em dash so it fits on two lines.
	tagline="$(sed 's/ — /\n/' "$dir/short_description.txt")"
	out="$dir/images/featureGraphic.png"

	magick -size 1024x500 "xc:$BG" \
		\( "$ICON" -resize 440x440 \) -gravity west -geometry +30+0 -composite \
		-gravity northwest \
		-font "$FONT_DIR/Roboto-Bold.ttf" -pointsize 110 -fill white \
		-annotate +480+142 "$title" \
		-font "$FONT_DIR/Roboto-Regular.ttf" -pointsize 30 -fill "#C5CAE9" \
		-interline-spacing 8 -annotate +486+287 "$tagline" \
		-strip -depth 8 "$out"

	cp "$ICON.license" "$out.license"
	echo "$locale: $out"
done
