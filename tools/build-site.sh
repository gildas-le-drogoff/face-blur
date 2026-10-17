#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
#
# SPDX-License-Identifier: AGPL-3.0-or-later

# Builds the static GitHub Pages site from the fastlane metadata (the same texts
# and screenshots F-Droid shows): English at the root, French under fr/.
# Usage: tools/build-site.sh [output-dir]   (default: _site)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
METADATA="${ROOT}/fastlane/metadata/android"
OUT="${1:-${ROOT}/_site}"
REPO_URL="https://github.com/gildas-le-drogoff/face-blur"

escape() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g'; }
linkify() { sed -E 's#(https://[^ <]+[^ <.,;:])#<a href="\1">\1</a>#g'; }

# full_description.txt -> HTML: blank-line separated paragraphs, "• " lines as a list.
description_html() {
	local in_list=false line
	while IFS= read -r line || [ -n "$line" ]; do
		if [[ $line == "• "* ]]; then
			$in_list || {
				echo "<ul>"
				in_list=true
			}
			echo "<li>${line#• }</li>"
		else
			$in_list && {
				echo "</ul>"
				in_list=false
			}
			[ -n "$line" ] && echo "<p>${line}</p>"
		fi
	done < <(escape < "$1" | linkify)
	$in_list && echo "</ul>"
	return 0
}

# $1 fastlane locale, $2 html lang, $3 output subdir ("" = root), $4 asset prefix,
# $5 other-language link, $6 other-language label, then the UI strings.
render() {
	local locale="$1" lang="$2" subdir="$3" prefix="$4" other_href="$5" other_label="$6"
	local download="$7" source="$8" screenshots_title="$9" screenshot_alt="${10}" fdroid="${11}"
	local dir="${METADATA}/${locale}"
	local title short shots=""
	title="$(escape < "${dir}/title.txt")"
	short="$(escape < "${dir}/short_description.txt")"

	mkdir -p "${OUT}/${subdir}" "${OUT}/screenshots/${locale}"
	local png n=0
	for png in "${dir}"/images/phoneScreenshots/*.png; do
		n=$((n + 1))
		cp "$png" "${OUT}/screenshots/${locale}/"
		shots+="<li><a href=\"${prefix}screenshots/${locale}/${png##*/}\"><img src=\"${prefix}screenshots/${locale}/${png##*/}\" alt=\"${screenshot_alt} ${n}\" loading=\"lazy\" width=\"1080\" height=\"1920\"></a></li>"$'\n'
	done

	cat > "${OUT}/${subdir}index.html" << EOF
<!DOCTYPE html>
<html lang="${lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} — ${short}</title>
<meta name="description" content="${short}">
<link rel="icon" href="${prefix}icon.png">
<link rel="stylesheet" href="${prefix}style.css">
</head>
<body>
<header>
<nav><a href="${other_href}" hreflang="$([ "$lang" = en ] && echo fr || echo en)">${other_label}</a></nav>
<img class="icon" src="${prefix}icon.png" alt="" width="128" height="128">
<h1>${title}</h1>
<p class="tagline">${short}</p>
<p class="actions">
<a class="button primary" href="${REPO_URL}/releases/latest">${download}</a>
<a class="button" href="${REPO_URL}">${source}</a>
</p>
<p class="note">${fdroid}</p>
</header>
<main>
<section class="description">
$(description_html "${dir}/full_description.txt")
</section>
<section>
<h2>${screenshots_title}</h2>
<ul class="screenshots">
${shots}</ul>
</section>
</main>
<footer>
<p>© 2026 Gildas Le Drogoff — <a href="${REPO_URL}/blob/main/LICENSE">AGPL-3.0-or-later</a></p>
</footer>
</body>
</html>
EOF
}

rm -rf "$OUT"
mkdir -p "$OUT"
cp "${METADATA}/en-US/images/icon.png" "${OUT}/icon.png"

cat > "${OUT}/style.css" << 'EOF'
:root { color-scheme: light dark; --bg: #fdfcff; --fg: #1a1c1e; --muted: #5d5e62; --accent: #0b57d0; --on-accent: #ffffff; --card: #eef0f6; }
@media (prefers-color-scheme: dark) { :root { --bg: #111318; --fg: #e3e2e6; --muted: #a9aab0; --accent: #a8c7fa; --on-accent: #062e6f; --card: #1e2026; } }
* { box-sizing: border-box; }
body { margin: 0; font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; line-height: 1.6; background: var(--bg); color: var(--fg); }
header, main, footer { max-width: 60rem; margin: 0 auto; padding: 1.5rem; }
header { text-align: center; }
nav { text-align: right; }
a { color: var(--accent); }
.icon { width: 8rem; height: 8rem; border-radius: 22%; }
h1 { margin: 0.5rem 0 0; font-size: 2.5rem; }
.tagline { font-size: 1.25rem; color: var(--muted); margin-top: 0.25rem; }
.actions { display: flex; flex-wrap: wrap; gap: 0.75rem; justify-content: center; }
.button { display: inline-block; padding: 0.6rem 1.4rem; border-radius: 999px; border: 1px solid var(--accent); text-decoration: none; font-weight: 600; }
.button.primary { background: var(--accent); color: var(--on-accent); }
.note { color: var(--muted); font-size: 0.9rem; }
.description { background: var(--card); border-radius: 1rem; padding: 0.5rem 1.5rem; }
.screenshots { list-style: none; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(10rem, 1fr)); gap: 1rem; }
.screenshots img { width: 100%; height: auto; border-radius: 0.75rem; box-shadow: 0 2px 8px rgb(0 0 0 / 0.25); }
footer { text-align: center; color: var(--muted); font-size: 0.9rem; }
EOF

render en-US en "" "" "fr/" "Version française" \
	"Download the APK" "Source code" "Screenshots" "Screenshot" \
	"Coming soon on F-Droid."
render fr-FR fr "fr/" "../" "../" "English version" \
	"Télécharger l'APK" "Code source" "Captures d'écran" "Capture d'écran" \
	"Bientôt disponible sur F-Droid."

echo "Site built in ${OUT}"
