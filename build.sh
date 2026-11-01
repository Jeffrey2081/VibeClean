#!/usr/bin/env bash
# ==============================================================================
# OneTap Cleaner - Standalone APK Compiler & Signer
# ==============================================================================

set -e

PROJECT_DIR="/home/jeff/OneTapCleaner"
export ANDROID_HOME="${ANDROID_HOME:-/home/jeff/.android_sdk}"
BUILD_TOOLS_DIR="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM_DIR="$ANDROID_HOME/platforms/android-34"
ANDROID_JAR="$PLATFORM_DIR/android.jar"

AAPT2="$BUILD_TOOLS_DIR/aapt2"
D8="$BUILD_TOOLS_DIR/d8"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"

echo "=== Building OneTap Cleaner APK ==="
echo "Build Tools: $BUILD_TOOLS_DIR"
echo "Android Platform: $PLATFORM_DIR"

# Prepare workspace
BUILD_TMP="$PROJECT_DIR/build_tmp"
rm -rf "$BUILD_TMP"
mkdir -p "$BUILD_TMP/compiled_res" "$BUILD_TMP/obj" "$BUILD_TMP/dex" "$BUILD_TMP/src" "$PROJECT_DIR/dist"

# Step 1: Compile Resources
echo "→ Step 1/6: Compiling resources..."
"$AAPT2" compile --dir "$PROJECT_DIR/res" -o "$BUILD_TMP/compiled_res.zip"

# Step 2: Link Resources & Manifest
echo "→ Step 2/6: Linking package..."
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    -o "$BUILD_TMP/app-unaligned.apk" \
    --java "$BUILD_TMP/src" \
    "$BUILD_TMP/compiled_res.zip" \
    -A "$PROJECT_DIR/assets"

# Step 3: Compile Java Sources
echo "→ Step 3/6: Compiling Java source code..."
JAVA_FILES=$(find "$PROJECT_DIR/src" -name "*.java")
R_FILES=$(find "$BUILD_TMP/src" -name "*.java")

javac -g -source 8 -target 8 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_TMP/obj" \
    $JAVA_FILES $R_FILES

# Step 4: Dex bytecode with D8
echo "→ Step 4/6: Dexing bytecode (D8)..."
"$D8" --lib "$ANDROID_JAR" --output "$BUILD_TMP/dex" \
    $(find "$BUILD_TMP/obj" -name "*.class")

# Add classes.dex into APK
jar -uf "$BUILD_TMP/app-unaligned.apk" -C "$BUILD_TMP/dex" classes.dex

# Step 5: Align APK
echo "→ Step 5/6: Aligning APK with zipalign..."
"$ZIPALIGN" -f -p 4 "$BUILD_TMP/app-unaligned.apk" "$BUILD_TMP/app-aligned.apk"

# Step 6: Sign APK
echo "→ Step 6/6: Signing APK..."
KEYSTORE="$PROJECT_DIR/release.keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias onetap -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass onetap123 -keypass onetap123 -dname "CN=OneTapCleaner, OU=Mobile, O=OneTap, L=City, ST=State, C=US"
fi

FINAL_APK="$PROJECT_DIR/dist/VibeClean_v1.0.apk"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:onetap123 \
    --min-sdk-version 24 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$FINAL_APK" "$BUILD_TMP/app-aligned.apk"

# Copy final APK to user's Downloads
mkdir -p /home/jeff/Downloads
cp -f "$FINAL_APK" /home/jeff/Downloads/VibeClean_v1.0.apk
cp -f "$FINAL_APK" /home/jeff/Downloads/OneTap_Cleaner_v1.0.apk

# Clean build tmp
rm -rf "$BUILD_TMP"

echo "=========================================================="
echo "✓ BUILD SUCCESSFUL!"
echo "Final APK saved to: /home/jeff/Downloads/VibeClean_v1.0.apk"
echo "Also synced to:     /home/jeff/Downloads/OneTap_Cleaner_v1.0.apk"
echo "Size: $(du -h /home/jeff/Downloads/VibeClean_v1.0.apk | cut -f1)"
echo "=========================================================="
