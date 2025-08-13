#!/bin/bash
set -euo pipefail


# Android SDK path detection
if ! command -v avdmanager >/dev/null 2>&1; then
    echo "⚠️  avdmanager not found in PATH, searching for Android SDK..."
    ANDROID_PATHS=(
        "$HOME/Library/Android/sdk/cmdline-tools/latest/bin"
        "$HOME/Android/Sdk/cmdline-tools/latest/bin"
        "/usr/local/share/android-sdk/cmdline-tools/latest/bin"
    )
    for path in "${ANDROID_PATHS[@]}"; do
        if [[ -f "$path/avdmanager" ]]; then
            echo "✅ Found Android SDK at: $path"
            export PATH="$path:$PATH"
            break
        fi
    done
    if ! command -v avdmanager >/dev/null 2>&1; then
        echo "❌ Android SDK not found. Please install Android SDK and add avdmanager to PATH"
        echo "   Or set ANDROID_HOME/ANDROID_SDK_ROOT environment variable"
        exit 1
    fi
fi

# Android emulator path detection
if ! command -v emulator >/dev/null 2>&1; then
    echo "⚠️  emulator not found in PATH, searching for Android emulator..."
    EMULATOR_PATHS=(
        "$HOME/Library/Android/sdk/emulator"
        "$HOME/Android/Sdk/emulator"
        "/usr/local/share/android-sdk/emulator"
    )
    for path in "${EMULATOR_PATHS[@]}"; do
        if [[ -f "$path/emulator" ]]; then
            echo "✅ Found Android emulator at: $path"
            export PATH="$path:$PATH"
            break
        fi
    done
    if ! command -v emulator >/dev/null 2>&1; then
        echo "❌ Android emulator not found. Please install Android SDK emulator"
        echo "   Or ensure emulator is in PATH or ANDROID_HOME/emulator directory"
        exit 1
    fi
fi
# Architecture detection
if [[ $(uname -m) == "arm64" ]]; then
    IMG="system-images;android-34;google_apis;arm64-v8a"
else
    IMG="system-images;android-34;google_apis;x86_64"
fi

# Detect if we're in a session directory or create new session name
CURRENT_DIR=$(basename "$(pwd)")
if [[ "$CURRENT_DIR" =~ ^claude-[0-9]{8}-[0-9]{6} ]]; then
    SESSION_NAME="$CURRENT_DIR"
    echo "📁 Using existing session: $SESSION_NAME"
else
    SESSION_NAME="claude-$(date +%Y%m%d-%H%M%S)"
    echo "📁 Creating new session: $SESSION_NAME"
fi
EMULATOR_NAME="test-$SESSION_NAME"

echo "Creating emulator: $EMULATOR_NAME"
avdmanager create avd -n "$EMULATOR_NAME" -k "$IMG" -d "pixel_4" -f

# Find free port function
pick_port() {
  for p in $(seq 5554 2 5584); do
    if ! (adb devices | grep -q "emulator-$p"); then
      echo $p; return
    fi
  done
  echo "No free emulator port" >&2; exit 1
}

EMU_PORT=$(pick_port)

# Generate random ADB server port (macOS compatible)
ADB_PORT=$((5037 + RANDOM % 1000))
export ADB_SERVER_PORT=$ADB_PORT

echo "Starting emulator on port: $EMU_PORT"
echo "ADB server port: $ADB_SERVER_PORT"

# Start ADB server for this session
adb start-server

# Start emulator
echo "Starting emulator (logs: emulator.out, emulator.err)..."
echo "Starting emulator (logs: emulator.out, emulator.err)..."
emulator -avd "$EMULATOR_NAME" -port "$EMU_PORT" \
  -no-snapshot-save -no-boot-anim -noaudio -wipe-data >emulator.out 2>emulator.err &
disown

# Wait and set target
export ANDROID_SERIAL="emulator-$EMU_PORT"

# Wait for device to be fully ready
echo "Waiting for emulator to boot completely..."
adb -s "$ANDROID_SERIAL" wait-for-device

# Wait for system services to be ready
timeout=120
while [ $timeout -gt 0 ]; do
    if adb -s "$ANDROID_SERIAL" shell service list | grep -q "package" && \
       adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | grep -q "1"; then
        echo "Android boot completed"
        break
    fi
    echo "Waiting for Android to finish booting... ($timeout seconds remaining)"
    sleep 3
    timeout=$((timeout - 3))
done

# Additional wait for package manager
echo "Waiting for package manager to be ready..."
timeout=60
while [ $timeout -gt 0 ]; do
    if adb -s "$ANDROID_SERIAL" shell pm list packages >/dev/null 2>&1; then
        echo "Package manager ready"
        break
    fi
    echo "Waiting for package manager... ($timeout seconds remaining)"
    sleep 2
    timeout=$((timeout - 2))
done

echo "Device ready: $ANDROID_SERIAL"
echo "To use this device, run: export ANDROID_SERIAL=$ANDROID_SERIAL"
echo "To clean up, run: ./cleanup-session-device.sh $EMULATOR_NAME $ANDROID_SERIAL"

# Save session info for cleanup
echo "$EMULATOR_NAME:$ANDROID_SERIAL" > .claude-session-device

# Save individual values for easy access (avoids complex command substitution)
echo "$ANDROID_SERIAL" > .claude-device-serial
echo "$EMULATOR_NAME" > .claude-emulator-name

# Save app ID for easy access
./get-app-id.sh > .claude-app-id

# Create session environment file to eliminate command substitution completely
echo "# Claude Code session environment - source this file instead of using exports with command substitution" > .claude-env
echo "export ANDROID_SERIAL=\"$ANDROID_SERIAL\"" >> .claude-env
echo "export EMULATOR_NAME=\"$EMULATOR_NAME\"" >> .claude-env
echo "export APPID=\"$(./get-app-id.sh)\"" >> .claude-env
echo "# Session created: $(date)" >> .claude-env

echo ""
echo "📝 Session environment saved to .claude-env"
echo "💡 Scripts can now use: source .claude-env (instead of complex exports)"
