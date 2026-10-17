#!/bin/bash

# SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
#
# SPDX-License-Identifier: AGPL-3.0-or-later

# Generates the fastlane screenshots (F-Droid and Google Play) in English and
# French from an emulator, for phones and 7" / 10" tablets.
# Usage: ./generate-screenshots.sh [--build] [phone] [sevenInch] [tenInch]
#   --build: compile and install the debug APK before taking screenshots.
#   Form factors: those to capture (all by default).
# Without a running emulator, one is launched (FACEBLUR_AVD, or the first AVD
# with API 33+) and left running; if it stops mid-run, it is relaunched and
# the interrupted form factor redone.
# The emulator's display is resized (wm size / density) so every form factor
# gets a Google Play compliant 9:16 or 16:9 format, whatever the emulator;
# the PNGs are written without alpha channel nor metadata (24-bit RGB).
# The app's data is cleared on the emulator; the demo photo (face generated
# by StyleGAN2, no real person) is copied there and then deleted at the end.
# ANDROID_SERIAL lets you choose the emulator if several are running.
# Requires adb and ImageMagick; optipng, if present, shrinks the files.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE="fr.gildasledrogoff.faceblur"
ACTIVITY="${PACKAGE}/.MainActivity"
METADATA_DIR="${PROJECT_DIR}/fastlane/metadata/android"
RES_DIR="${PROJECT_DIR}/app/src/main/res"
DEMO_IMAGE="${PROJECT_DIR}/app/src/debug/assets/screenshot_demo.jpg"
DEMO_NAME="faceblur_screenshot_demo.jpg"
DEMO_DIR="/sdcard/Pictures/FaceBlurDemo"
# Face blurred in the editor screenshots (the man in the center), in pixels
# of the demo photo.
DEMO_FACE_X=302
DEMO_FACE_Y=377
UI_DUMP="/sdcard/faceblur-ui.xml"
# fastlane locale -> Android locale -> resource folder.
LOCALES=("en-US:en-US:values" "fr-FR:fr-FR:values-fr")
# Form factor -> width -> height -> density. Google Play: 9:16 or 16:9,
# 1080 px minimum per side. Phone: 411 x 731 dp (compact layout); 7" tablet:
# 617 x 1097 dp portrait (medium, stacked); 10" tablet: 1280 x 720 dp
# landscape (expanded, side by side).
FORM_FACTORS=("phone:1080:1920:420" "sevenInch:1080:1920:280" "tenInch:2560:1440:320")
# Google Play limit per screenshot.
MAX_BYTES=$((8 * 1024 * 1024))
# AVD launched when no emulator runs (by default that of the running one).
AVD="${FACEBLUR_AVD:-}"
BOOT_TIMEOUT=300
# Attempts per form factor, the emulator being relaunched between them.
MAX_ATTEMPTS=3

RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
PURPLE=$'\033[0;35m'
NC=$'\033[0m'

log() { printf '%b\n' "$1"; }
# On stderr: still visible when called from a command substitution.
die() {
	log "${RED}$1${NC}" >&2
	exit 1
}

# --- Device -----------------------------------------------------------------

emulator_bin() {
	local sdk dir
	sdk="$(sed -n 's/^sdk\.dir=//p' "${PROJECT_DIR}/local.properties" 2> /dev/null || true)"
	for dir in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$sdk" "${HOME}/Android/Sdk"; do
		if [ -n "$dir" ] && [ -x "${dir}/emulator/emulator" ]; then
			printf '%s' "${dir}/emulator/emulator"
			return 0
		fi
	done
	command -v emulator || die "Android emulator not found (ANDROID_HOME, sdk.dir in local.properties)."
}

# API level of an AVD, read from its system image (empty if unknown).
avd_api() {
	local home="${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-${HOME}/.android}/avd}"
	sed -n 's|^image\.sysdir\.1=.*android-\([0-9]*\).*|\1|p' "${home}/$1.avd/config.ini" 2> /dev/null |
		head -n1
}

pick_avd() {
	local bin name api
	[ -z "$AVD" ] || return 0
	bin="$(emulator_bin)"
	while read -r name; do
		api="$(avd_api "$name")"
		if [ -n "$api" ] && [ "$api" -ge 33 ]; then
			AVD="$name"
			return 0
		fi
	done < <("$bin" -list-avds 2> /dev/null | grep -E '^[[:alnum:]._-]+$')
	die "No AVD with API 33+: create one or set FACEBLUR_AVD."
}

# Serial present in adb devices, whatever its state.
device_listed() {
	adb devices | awk -v serial="$1" '$1 == serial { found = 1 } END { exit !found }'
}

emulator_ready() {
	[ "$(adb get-state 2> /dev/null)" = device ] &&
		[ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = 1 ]
}

# wait_for_boot <seconds> [emulator pid]: fails on timeout, or as soon as the
# process launched here exits.
wait_for_boot() {
	local i
	for ((i = 0; i < $1; i++)); do
		emulator_ready && return 0
		[ -z "${2:-}" ] || kill -0 "$2" 2> /dev/null || return 1
		sleep 1
	done
	return 1
}

# Cold boot: a Quick Boot snapshot would bring back an older userdata, maybe
# without the app. Its own port gives the serial without ambiguity.
launch_emulator() {
	local bin port=5554 pid logfile
	bin="$(emulator_bin)"
	pick_avd
	while device_listed "emulator-${port}"; do
		port=$((port + 2))
	done
	ANDROID_SERIAL="emulator-${port}"
	export ANDROID_SERIAL
	logfile="${TMPDIR:-/tmp}/faceblur-emulator-${port}.log"
	log "${GREEN}Launching the emulator ${PURPLE}${AVD}${GREEN} (${ANDROID_SERIAL}, log: ${logfile})...${NC}"
	nohup "$bin" -avd "$AVD" -port "$port" -no-snapshot -no-boot-anim -no-audio \
		< /dev/null > "$logfile" 2>&1 &
	pid=$!
	wait_for_boot "$BOOT_TIMEOUT" "$pid" ||
		die "The emulator did not boot, see ${logfile}"
}

# Prefers an emulator ready to use, else waits for one still booting, else
# launches one.
pick_emulator() {
	if [ -z "${ANDROID_SERIAL:-}" ]; then
		ANDROID_SERIAL="$(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')"
		[ -n "$ANDROID_SERIAL" ] ||
			ANDROID_SERIAL="$(adb devices | awk '$1 ~ /^emulator-/ { print $1; exit }')"
	fi
	export ANDROID_SERIAL
	if [ -z "$ANDROID_SERIAL" ] || ! device_listed "$ANDROID_SERIAL"; then
		launch_emulator
	else
		wait_for_boot "$BOOT_TIMEOUT" || die "${ANDROID_SERIAL} does not respond (adb devices)."
		[ -n "$AVD" ] || AVD="$(adb emu avd name 2> /dev/null | head -n1 | tr -d '\r')"
	fi
	local qemu
	qemu="$(adb shell 'getprop ro.boot.qemu; getprop ro.kernel.qemu' | tr -d '\r\n')"
	[[ $qemu == *1* ]] || die "${ANDROID_SERIAL} is not an emulator: refusing to wipe its data."
	local sdk
	sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
	[ "$sdk" -ge 33 ] || die "API ${sdk}: per-app language requires API 33+."
	log "${GREEN}Emulator: ${PURPLE}${ANDROID_SERIAL}${GREEN} (API ${sdk})${NC}"
}

install_app() {
	log "${GREEN}Building + installing the debug APK...${NC}"
	(cd "$PROJECT_DIR" && ./gradlew :app:installDebug --console=plain -q)
}

require_installed() {
	adb shell pm path "$PACKAGE" | grep -q '^package:' ||
		die "${PACKAGE} not present on the emulator: rerun with --build."
}

# Simulates the form factor: screencap then returns exactly this size.
SCREEN_W=0
SCREEN_H=0
set_screen() {
	SCREEN_W="$1"
	SCREEN_H="$2"
	adb shell wm size "${SCREEN_W}x${SCREEN_H}"
	adb shell wm density "$3"
	sleep 2
}

# Natural orientation, whatever the emulator's sensor reports. The initial
# setting is kept across a relaunch of the emulator.
ROTATION=""
lock_rotation() {
	[ -n "$ROTATION" ] ||
		ROTATION="$(adb shell settings get system accelerometer_rotation | tr -d '\r')"
	adb shell settings put system accelerometer_rotation 0
	adb shell settings put system user_rotation 0
}

set_animations() {
	local scale
	for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
		adb shell settings put global "$scale" "$1"
	done
}

demo_mode() {
	adb shell am broadcast -a com.android.systemui.demo -e command "$@" > /dev/null
}

# Reapplied after each resize: the recreated system UI would otherwise
# duplicate the network icons. The emulator's mobile network is hidden, its
# "3G" icon would show anyway.
enter_demo_mode() {
	adb shell settings put global sysui_demo_allowed 1
	demo_mode exit
	demo_mode enter
	demo_mode clock -e hhmm 1200
	demo_mode battery -e level 100 -e plugged false
	demo_mode network -e wifi show -e level 4 -e fully true
	demo_mode network -e mobile hide
	demo_mode notifications -e visible false
	sleep 1
}

restore_device() {
	demo_mode exit || true
	set_animations 1 || true
	adb shell wm size reset || true
	adb shell wm density reset || true
	if [[ $ROTATION =~ ^[01]$ ]]; then
		adb shell settings put system accelerometer_rotation "$ROTATION" || true
	fi
	remove_demo_image || true
	adb shell rm -f "$UI_DUMP" || true
	rm -rf "$TMP_DIR"
}

# --- Demo photo -------------------------------------------------------------

# MediaStore ID of the demo photo (empty if absent).
demo_image_id() {
	adb shell "content query --uri content://media/external/images/media --projection _id --where \"_display_name='${DEMO_NAME}'\"" |
		sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -n1
}

remove_demo_image() {
	adb shell "content delete --uri content://media/external/images/media --where \"_display_name='${DEMO_NAME}'\"" > /dev/null 2>&1 || true
	adb shell rm -rf "$DEMO_DIR"
}

# Copies the photo into the gallery: only a content:// URI can be handed to the app.
DEMO_URI=""
push_demo_image() {
	local id i
	[ -f "$DEMO_IMAGE" ] || die "Demo photo not found: ${DEMO_IMAGE}"
	remove_demo_image
	adb shell mkdir -p "$DEMO_DIR"
	adb push "$DEMO_IMAGE" "${DEMO_DIR}/${DEMO_NAME}" > /dev/null 2>&1
	adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
		-d "file://${DEMO_DIR}/${DEMO_NAME}" > /dev/null
	for ((i = 0; i < 20; i++)); do
		id="$(demo_image_id)"
		if [ -n "$id" ]; then
			DEMO_URI="content://media/external/images/media/${id}"
			log "${GREEN}Demo photo: ${PURPLE}${DEMO_URI}${NC}"
			return 0
		fi
		sleep 0.5
	done
	die "Demo photo never indexed by MediaStore."
}

# --- Text resources ---------------------------------------------------------

# Raw value of a <string>, possibly spread over several lines, with the
# whitespace collapsed as aapt does: string_value <values folder> <name>.
string_value() {
	tr '\n' ' ' < "${RES_DIR}/$1/strings.xml" | sed 's/<string /\n&/g' |
		sed -n "s|^<string name=\"$2\"[^>]*>\(.*\)</string *>.*|\1|p" |
		sed -e 's/[[:space:]]\+/ /g' -e 's/^ //' -e 's/ $//' | head -n1
}

# Translated Android string: string_res <values folder> <name>.
string_res() {
	local value
	value="$(string_value "$1" "$2")"
	[ -n "$value" ] || value="$(string_value values "$2")"
	[ -n "$value" ] || die "String not found: $2"
	value="${value//\\\'/\'}"
	value="${value//\\\"/\"}"
	value="${value//&amp;/\&}"
	printf '%s' "$value"
}

# --- UI ---------------------------------------------------------------------

ui_nodes() {
	adb shell uiautomator dump "$UI_DUMP" > /dev/null 2>&1 || return 0
	adb exec-out cat "$UI_DUMP" | grep -o '<node [^>]*>' |
		sed -e "s/&apos;/'/g" -e "s/&#39;/'/g" -e 's/&quot;/"/g' -e 's/&amp;/\&/g'
}

# Center of the first node whose text or description exactly matches the label.
node_center() {
	grep -F -e "text=\"$1\"" -e "content-desc=\"$1\"" | head -n1 | bounds_center
}

bounds_center() {
	sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' |
		awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }'
}

find_text() {
	ui_nodes | node_center "$1" || true
}

wait_for_text() {
	local label="$1" tries="${2:-30}" point i
	for ((i = 0; i < tries; i++)); do
		point="$(find_text "$label")"
		[ -n "$point" ] && return 0
		sleep 1
	done
	die "Text not found on screen: \"${label}\""
}

wait_until_gone() {
	local label="$1" tries="${2:-30}" i
	for ((i = 0; i < tries; i++)); do
		[ -z "$(find_text "$label")" ] && return 0
		sleep 1
	done
	die "Still on screen: \"${label}\""
}

tap() {
	adb shell input tap "$1" "$2"
}

tap_text() {
	local point
	wait_for_text "$1" "${2:-30}"
	point="$(find_text "$1")"
	# shellcheck disable=SC2086
	tap $point
}

# The gesture must start from the settings panel rather than the preview,
# where a swipe would draw a blur area: below it on a portrait screen (from
# the bottom), to its right in landscape (side-by-side layout).
scroll_down() {
	local x=$((SCREEN_W / 2))
	[ "$SCREEN_W" -gt "$SCREEN_H" ] && x=$((SCREEN_W * 3 / 4))
	adb shell input swipe "$x" $((SCREEN_H * 80 / 100)) "$x" $((SCREEN_H * 50 / 100)) 300
	sleep 1
}

# Scrolls until the label is visible, then taps it.
scroll_to_and_tap() {
	local point i
	for ((i = 0; i < 8; i++)); do
		point="$(find_text "$1")"
		if [ -n "$point" ]; then
			# shellcheck disable=SC2086
			tap $point
			return 0
		fi
		scroll_down
	done
	die "Element not found after scrolling: \"$1\""
}

# --- App --------------------------------------------------------------------

reset_app() {
	adb shell am force-stop "$PACKAGE"
	adb shell pm clear "$PACKAGE" > /dev/null
}

set_app_locale() {
	adb shell cmd locale set-app-locales "$PACKAGE" --locales "$1"
}

# "Edit with…": as from a gallery, with read permission on the URI.
open_demo_image() {
	adb shell am start -W -n "$ACTIVITY" -a android.intent.action.EDIT \
		-t image/jpeg -d "$DEMO_URI" --grant-read-uri-permission > /dev/null
}

# The received image is loaded during onboarding: the editor then displays it,
# with the preview toggle that only exists once an image is open.
wait_for_editor() {
	local res="$1"
	wait_for_text "$(string_res "$res" show_result)"
	wait_until_gone "$(string_res "$res" loading)"
	wait_until_gone "$(string_res "$res" rendering)"
}

# Taps a detected face to blur it. The preview, found through its description
# ("blurred zones: 0 of N"), fits the photo and centers it. A tap beside the
# face would draw a manual zone: the count must then read "1 of N".
blur_demo_face() {
	local res="$1" desc prefix node total left top right bottom img_w img_h point
	desc="$(string_res "$res" cd_preview)"
	prefix="${desc%%"%1\$d"*}"
	node="$(ui_nodes | grep -F "content-desc=\"${prefix}" | head -n1)"
	[ -n "$node" ] || die "Preview not found on screen: \"${prefix}\""
	total="$(sed -E 's/.*content-desc="[^"]*[^0-9]([0-9]+)".*/\1/' <<< "$node")"
	read -r left top right bottom < <(sed -E \
		's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' <<< "$node")
	read -r img_w img_h < <("${IDENTIFY[@]}" -format '%w %h\n' "$DEMO_IMAGE")
	point="$(awk -v l="$left" -v t="$top" -v r="$right" -v b="$bottom" \
		-v iw="$img_w" -v ih="$img_h" -v fx="$DEMO_FACE_X" -v fy="$DEMO_FACE_Y" 'BEGIN {
			w = r - l; h = b - t
			s = (w / iw < h / ih) ? w / iw : h / ih
			print int(l + (w - iw * s) / 2 + fx * s), int(t + (h - ih * s) / 2 + fy * s)
		}')"
	# shellcheck disable=SC2086
	tap $point
	desc="${desc//"%1\$d"/1}"
	wait_for_text "${desc//"%2\$d"/$total}" 10
	wait_until_gone "$(string_res "$res" rendering)"
}

# --- Screenshots ------------------------------------------------------------

TMP_DIR=""
MAGICK=()
IDENTIFY=()
OPTIPNG=""
find_image_tools() {
	if command -v magick > /dev/null; then
		MAGICK=(magick)
		IDENTIFY=(magick identify)
	elif command -v convert > /dev/null && command -v identify > /dev/null; then
		MAGICK=(convert)
		IDENTIFY=(identify)
	else
		die "ImageMagick not found in PATH."
	fi
	OPTIPNG="$(command -v optipng || true)"
}

# Waits until the screen no longer changes (two identical frames in a row).
stable_screencap() {
	local prev="${TMP_DIR}/prev.png" cur="${TMP_DIR}/cur.png" i
	sleep 1
	adb exec-out screencap -p > "$prev"
	for ((i = 0; i < 10; i++)); do
		sleep 0.5
		adb exec-out screencap -p > "$cur"
		cmp -s "$prev" "$cur" && break
		mv "$cur" "$prev"
	done
	[ "$i" -lt 10 ] || log "  ${RED}Screen still changing, keeping the last frame.${NC}"
	mv "$prev" "$1"
}

# Google Play rejects alpha channels: 24-bit RGB, without date metadata so
# that an unchanged screen gives an identical file.
capture() {
	local file="$1" info size
	stable_screencap "${TMP_DIR}/raw.png"
	"${MAGICK[@]}" "${TMP_DIR}/raw.png" -alpha off -strip \
		-define png:exclude-chunk=date,time "PNG24:${file}"
	# -nc -nb: keep 24-bit RGB rather than a palette.
	[ -z "$OPTIPNG" ] || "$OPTIPNG" -quiet -o2 -nc -nb "$file"
	info="$("${IDENTIFY[@]}" -format '%wx%h %[type]' "$file")"
	[ "$info" = "${SCREEN_W}x${SCREEN_H} TrueColor" ] ||
		die "Unexpected screenshot (${info}, expected ${SCREEN_W}x${SCREEN_H} TrueColor): ${file}"
	size="$(stat -c %s "$file")"
	[ "$size" -le "$MAX_BYTES" ] || die "Screenshot over 8 MB: ${file}"
	log "  ${GREEN}✅ ${PURPLE}${file#"${PROJECT_DIR}"/}${GREEN} (${info%% *}, $((size / 1024)) KB)${NC}"
}

capture_locale() {
	local fastlane="$1" android="$2" res="$3" form="$4" out
	out="${METADATA_DIR}/${fastlane}/images/${form}Screenshots"
	log "${GREEN}Screenshots ${PURPLE}${fastlane} ${form}${NC}"
	mkdir -p "$out"
	rm -f "$out"/*.png
	reset_app
	set_app_locale "$android"
	open_demo_image

	tap_text "$(string_res "$res" onboarding_next)"
	wait_for_text "$(string_res "$res" onboarding_start)"
	capture "${out}/3_onboarding.png"
	tap_text "$(string_res "$res" onboarding_start)"

	wait_for_editor "$res"
	blur_demo_face "$res"
	capture "${out}/1_editor.png"

	tap_text "$(string_res "$res" show_result)"
	scroll_to_and_tap "$(string_res "$res" blur_type_gaussian)"
	sleep 1
	wait_until_gone "$(string_res "$res" rendering)"
	capture "${out}/2_editor_gaussian.png"

	tap_text "$(string_res "$res" settings_title)"
	wait_for_text "$(string_res "$res" settings_theme)"
	capture "${out}/4_settings.png"

	scroll_to_and_tap "$(string_res "$res" credits_title)"
	wait_for_text "$(string_res "$res" credits_section_developer)"
	capture "${out}/5_credits.png"

	adb shell am force-stop "$PACKAGE"
}

capture_form_factor() {
	local form width height density locale fastlane android res
	IFS=':' read -r form width height density <<< "$1"
	set_screen "$width" "$height" "$density"
	enter_demo_mode
	for locale in "${LOCALES[@]}"; do
		IFS=':' read -r fastlane android res <<< "$locale"
		capture_locale "$fastlane" "$android" "$res" "$form"
	done
}

prepare_device() {
	require_installed
	set_animations 0
	lock_rotation
	push_demo_image
}

# The emulator may stop mid-run (crash, or idle timeout of the one embedded
# in Android Studio): it is then relaunched and the form factor redone. A
# failure with the emulator still there is a real error.
capture_with_retries() {
	local attempt status
	for ((attempt = 1; ; attempt++)); do
		# Subshell: die only ends this attempt. Outside a condition so that
		# set -e stays active inside.
		set +e
		(
			set -e
			capture_form_factor "$1"
		)
		status=$?
		set -e
		[ "$status" -ne 0 ] || return 0
		wait_for_boot 10 && exit "$status"
		[ "$attempt" -lt "$MAX_ATTEMPTS" ] ||
			die "The emulator stopped ${attempt} times, giving up."
		log "${RED}Emulator ${ANDROID_SERIAL} lost: relaunching it.${NC}"
		launch_emulator
		prepare_device
	done
}

main() {
	local build=0 arg entry selected=() forms=()
	for arg in "$@"; do
		case "$arg" in
		--build) build=1 ;;
		-h | --help)
			# Header comment, after the licence.
			awk 'NR >= 7 && !/^#/ { exit } NR >= 7 { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
			exit 0
			;;
		*)
			for entry in "${FORM_FACTORS[@]}"; do
				[ "${entry%%:*}" = "$arg" ] && selected+=("$entry")
			done
			[ "${#selected[@]}" -gt "${#forms[@]}" ] || die "Unknown option or form factor: ${arg}"
			forms=("${selected[@]}")
			;;
		esac
	done
	[ "${#forms[@]}" -gt 0 ] || forms=("${FORM_FACTORS[@]}")

	command -v adb > /dev/null || die "adb not found in PATH."
	find_image_tools
	pick_emulator
	[ "$build" -eq 0 ] || install_app
	require_installed
	trap restore_device EXIT
	TMP_DIR="$(mktemp -d)"
	prepare_device
	for entry in "${forms[@]}"; do
		capture_with_retries "$entry"
	done
	reset_app
	log "${GREEN}Done.${NC}"
}

main "$@"
