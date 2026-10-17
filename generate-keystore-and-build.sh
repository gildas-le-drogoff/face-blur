#!/bin/bash
PROJECT_DIR="/home/gildas/AndroidStudioProjects/InsightFace"
KEYSTORE_FILE="upload-keystore.jks"
KEYSTORE_PROPS="keystore.properties"
KEY_ALIAS="upload"
ARTIFACT_BASENAME="0_Insightface_Android"
DATE_TAG="$(date +%F_%H)"
FINAL_APK="${ARTIFACT_BASENAME}.apk"
STAMPED_APK="${ARTIFACT_BASENAME}_${DATE_TAG}.apk"
FINAL_DEBUG_APK="${ARTIFACT_BASENAME}_debug.apk"
SYNC_DIR="/mnt/NUMBER_4/Sync_Gildas_s_Phone/Orion/0_AppDev"

RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
PURPLE=$'\033[0;35m'
YELLOW=$'\033[1;33m'
NC=$'\033[0m'

log() { printf '%b\n' "$1"; }

generate_upload_keystore() {
	local password
	password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"

	keytool -genkeypair -v -keystore "$KEYSTORE_FILE" -alias "$KEY_ALIAS" \
		-keyalg RSA -keysize 4096 -validity 10950 \
		-storepass "$password" -keypass "$password" \
		-dname "CN=Insightface_Upload Key,O=ledrogoff,C=FR"

	umask 077
	cat > "$KEYSTORE_PROPS" << EOF
storeFile=../${KEYSTORE_FILE}
storePassword=${password}
keyAlias=${KEY_ALIAS}
keyPassword=${password}
EOF
	chmod 600 "$KEYSTORE_PROPS"

	log "${YELLOW}⚠️  NEW upload keystore generated (one-shot)."
	log "⚠️  Back up ${KEYSTORE_FILE} and ${KEYSTORE_PROPS} somewhere safe NOW."
	log "⚠️  Losing them means losing the Play upload key.${NC}"
}

setup_keystore() {
	if [ -f "$KEYSTORE_FILE" ] && [ -f "$KEYSTORE_PROPS" ]; then
		log "${GREEN}Reusing existing upload keystore.${NC}"
		return
	fi
	if [ -f "$KEYSTORE_FILE" ] && [ ! -f "$KEYSTORE_PROPS" ]; then
		log "${RED}${KEYSTORE_FILE} exists but ${KEYSTORE_PROPS} is missing."
		log "Password is unrecoverable: restore ${KEYSTORE_PROPS} from backup.${NC}"
		exit 1
	fi
	log "${GREEN}Generating upload keystore (one-shot)...${NC}"
	generate_upload_keystore
}

cd "$PROJECT_DIR"

setup_keystore

log "${GREEN}Format code...${NC}"
# ktlint -F ./app/src 2> /dev/null || true

log "${GREEN}Build release APK + debug APK...${NC}"

# ● Bash(./gradlew :app:compileDebugKotlin --write-locks --console=plain -q 2>&1 | grep -v "^w:" | tail -30)
# ./gradlew assembleDebug --write-locks --console=plain    # --parallel --configure-on-demand

./gradlew assembleDebug  --console=plain    # --parallel --configure-on-demand
./gradlew assembleRelease   --console=plain -q 2>&1 # --parallel --configure-on-demand

APK_PATH="$(find "${PROJECT_DIR}/app/build/outputs/apk/release" -name '*.apk' | head -n1)"
DEBUG_APK_PATH="$(find "${PROJECT_DIR}/app/build/outputs/apk/debug" -name '*.apk' | head -n1)"

if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
	log "${RED}Release APK build failed or not found.${NC}"
	exit 1
fi

cp "$APK_PATH" "${PROJECT_DIR}/${FINAL_APK}"
cp "$DEBUG_APK_PATH" "${PROJECT_DIR}/${FINAL_DEBUG_APK}"

log "${GREEN}✅ APK (release) -> ${PURPLE}${PROJECT_DIR}/${FINAL_APK}${NC}"
log "${GREEN}✅ APK (debug)   -> ${PURPLE}${PROJECT_DIR}/${FINAL_DEBUG_APK}${NC}"
log "${RED}__________________________${NC}"

CURRENT_HOST="$(hostname -s)"
PUSH="true"
if [ "$PUSH" = "true" ]; then
	if [ "$CURRENT_HOST" = "mercury" ]; then
		DEST="${SYNC_DIR}/${STAMPED_APK}"
		DEST_FINAL="${SYNC_DIR}/${FINAL_APK}"
		DEST_DEBUG="${SYNC_DIR}/${FINAL_DEBUG_APK}"
	else
		DEST="mercury:${SYNC_DIR}/${STAMPED_APK}"
		DEST_FINAL="mercury:${SYNC_DIR}/${FINAL_APK}"
		DEST_DEBUG="mercury:${SYNC_DIR}/${FINAL_DEBUG_APK}"
	fi
fi

rsync -ah -v --stats --info=progress2 "${PROJECT_DIR}/${FINAL_APK}" "$DEST"
rsync -ah -v --stats --info=progress2 "${PROJECT_DIR}/${FINAL_APK}" "$DEST_FINAL"
# rsync -ah -v --stats --info=progress2 "${PROJECT_DIR}/${FINAL_DEBUG_APK}" "$DEST_DEBUG"
