#!/usr/bin/env bash
set -euo pipefail

# ==============================================================
# Amereco Launcher — Cross-Platform Build & Packaging Script
# Builds distributable packages for Linux, Windows, and macOS
# entirely from a Linux machine.
# ==============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
TARGET_DIR="$PROJECT_DIR/target"
PACKAGES_DIR="$TARGET_DIR/packages"
VERSION="1.0.0"
ARTIFACT_ID="AmerecoLauncher"
MAIN_CLASS="ru.amereco.amerecolauncher.Main"
VENDOR="Amereco Minecraft Server"
DESCRIPTION="Amereco Minecraft Launcher"
URL="https://amereco.ru"
MAINTAINER="admin@amereco.ru"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

log()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
err()  { echo -e "${RED}[ERROR]${NC} $1"; }
title(){ echo -e "\n${CYAN}══════════════════════════════════════════════${NC}"; }
task() { echo -e "${CYAN}── $1 ──${NC}"; }

# ==============================================================
# Step 0: Prerequisites check
# ==============================================================
check_prerequisites() {
    title
    log "Checking prerequisites..."

    # Check required tools
    local required_tools=("java" "mvn" "jpackage" "jlink" "curl" "genisoimage")
    local optional_tools=("appimagetool" "dpkg-deb" "rpmbuild")

    for tool in "${required_tools[@]}"; do
        if command -v "$tool" &>/dev/null; then
            log "  ✓ $tool found: $(command -v $tool)"
        else
            err "  ✗ $tool not found. Please install it."
            MISSING_TOOLS+=("$tool")
        fi
    done

    for tool in "${optional_tools[@]}"; do
        if command -v "$tool" &>/dev/null; then
            log "  ✓ $tool found (optional): $(command -v $tool)"
        else
            warn "  ~ $tool not found (optional, some formats may be skipped)"
        fi
    done

    if [[ ${#MISSING_TOOLS[@]} -gt 0 ]]; then
        err "Missing required tools: ${MISSING_TOOLS[*]}"
        exit 1
    fi

    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
    if [[ "$JAVA_VERSION" -lt 17 ]]; then
        err "Java 17+ required, found: $(java -version 2>&1 | head -1)"
        exit 1
    fi
    log "  ✓ Java version: $(java -version 2>&1 | head -1)"
}

# ==============================================================
# Step 1: Build the cross-platform Shade (fat) JAR
# ==============================================================
build_shade_jar() {
    title
    task "Step 1: Building cross-platform Shade JAR"
    log "This will build a fat JAR containing JavaFX natives for ALL platforms"
    log "  - Windows (x86_64)"
    log "  - Linux (x86_64)"
    log "  - macOS (x86_64 + ARM64)"

    cd "$PROJECT_DIR"
    mvn clean package -Dpackager.shade -DskipTests

    local jar_name="${ARTIFACT_ID}-${VERSION}.jar"
    local shaded_jar="${TARGET_DIR}/${jar_name}"

    if [[ -f "$shaded_jar" ]]; then
        log "✓ Shade JAR created: $shaded_jar"
        log "  Size: $(du -h "$shaded_jar" | cut -f1)"
    else
        err "Shade JAR not found at $shaded_jar"
        exit 1
    fi
}

# ==============================================================
# Step 2: Download JDKs for target platforms
# ==============================================================
download_jdks() {
    title
    task "Step 2: Downloading JDK runtimes for all platforms"
    log "This step downloads JRE images from Adoptium for each target platform."
    log "These are used by jpackage to create self-contained bundles."

    mkdir -p "$TARGET_DIR/jdks"

    # Linux x86_64
    if [[ ! -d "$TARGET_DIR/jdks/linux-x64" ]]; then
        log "Downloading JDK for Linux x86_64..."
        curl -sL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jre/hotspot/normal/eclipse?project=jdk" \
            -o "$TARGET_DIR/jdks/linux-x64.tar.gz"
        mkdir -p "$TARGET_DIR/jdks/linux-x64"
        tar -xzf "$TARGET_DIR/jdks/linux-x64.tar.gz" -C "$TARGET_DIR/jdks/linux-x64" --strip-components=1
        log "  ✓ Linux x86_64 JRE ready"
    else
        log "  ~ Linux x86_64 JRE already cached, skipping"
    fi

    # Windows x86_64
    if [[ ! -d "$TARGET_DIR/jdks/windows-x64" ]]; then
        log "Downloading JDK for Windows x86_64..."
        curl -sL "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk" \
            -o "$TARGET_DIR/jdks/windows-x64.zip"
        mkdir -p "$TARGET_DIR/jdks/windows-x64"
        unzip -q "$TARGET_DIR/jdks/windows-x64.zip" -d "$TARGET_DIR/jdks/windows-x64"
        # Move contents up (Adoptium zip has a nested dir)
        if ls "$TARGET_DIR/jdks/windows-x64"/*/bin/java.exe &>/dev/null 2>&1; then
            local subdir
            subdir=$(ls -d "$TARGET_DIR/jdks/windows-x64"/*/ | head -1)
            mv "$subdir"/* "$TARGET_DIR/jdks/windows-x64/"
            rmdir "$subdir" 2>/dev/null || true
        fi
        log "  ✓ Windows x86_64 JRE ready"
    else
        log "  ~ Windows x86_64 JRE already cached, skipping"
    fi

    # macOS x86_64
    if [[ ! -d "$TARGET_DIR/jdks/mac-x64" ]]; then
        log "Downloading JDK for macOS x86_64..."
        curl -sL "https://api.adoptium.net/v3/binary/latest/17/ga/mac/x64/jre/hotspot/normal/eclipse?project=jdk" \
            -o "$TARGET_DIR/jdks/mac-x64.tar.gz"
        mkdir -p "$TARGET_DIR/jdks/mac-x64"
        tar -xzf "$TARGET_DIR/jdks/mac-x64.tar.gz" -C "$TARGET_DIR/jdks/mac-x64" --strip-components=1
        log "  ✓ macOS x86_64 JRE ready"
    else
        log "  ~ macOS x86_64 JRE already cached, skipping"
    fi

    # macOS ARM64 (Apple Silicon)
    if [[ ! -d "$TARGET_DIR/jdks/mac-aarch64" ]]; then
        log "Downloading JDK for macOS ARM64..."
        curl -sL "https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jre/hotspot/normal/eclipse?project=jdk" \
            -o "$TARGET_DIR/jdks/mac-aarch64.tar.gz"
        mkdir -p "$TARGET_DIR/jdks/mac-aarch64"
        tar -xzf "$TARGET_DIR/jdks/mac-aarch64.tar.gz" -C "$TARGET_DIR/jdks/mac-aarch64" --strip-components=1
        log "  ✓ macOS ARM64 JRE ready"
    else
        log "  ~ macOS ARM64 JRE already cached, skipping"
    fi

    # Linux ARM64 (optional)
#   if [[ ! -d "$TARGET_DIR/jdks/linux-aarch64" ]]; then
#       log "Downloading JDK for Linux ARM64..."
#       curl -sL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jre/hotspot/normal/eclipse?project=jdk" \
#           -o "$TARGET_DIR/jdks/linux-aarch64.tar.gz"
#       mkdir -p "$TARGET_DIR/jdks/linux-aarch64"
#       tar -xzf "$TARGET_DIR/jdks/linux-aarch64.tar.gz" -C "$TARGET_DIR/jdks/linux-aarch64" --strip-components=1
#       log "  ✓ Linux ARM64 JRE ready"
#   else
#       log "  ~ Linux ARM64 JRE already cached, skipping"
#   fi
}

# ==============================================================
# Step 3: Build Linux packages
# ==============================================================
build_linux_packages() {
    title
    task "Step 3: Building Linux packages"

    local jar_file="${TARGET_DIR}/${ARTIFACT_ID}-${VERSION}.jar"
    local output_dir="${PACKAGES_DIR}/linux"

    mkdir -p "$output_dir"

    # --- Method A: jpackage (self-contained .deb with JRE) ---
    if command -v jpackage &>/dev/null && command -v dpkg-deb &>/dev/null; then
        task "  ▶ Building Linux .deb (self-contained with JRE)..."
        jpackage \
            --input "$TARGET_DIR" \
            --main-jar "${ARTIFACT_ID}-${VERSION}.jar" \
            --main-class "$MAIN_CLASS" \
            --type deb \
            --name "AmerecoLauncher" \
            --app-version "$VERSION" \
            --vendor "$VENDOR" \
            --description "$DESCRIPTION" \
            --linux-package-name "amereco-launcher" \
            --linux-deb-maintainer "$MAINTAINER" \
            --linux-menu-group "Game" \
            --linux-app-category "Game" \
            --linux-shortcut \
            --icon "$PROJECT_DIR/assets/AmerecoLauncher.png" \
            --dest "$output_dir" \
            --java-options "-Dprism.verbose=true" \
            --java-options "--add-reads ru.amereco.amerecolauncher=ALL-UNNAMED" \
            --verbose 2>&1 | tail -5

        log "  ✓ Linux .deb package created"
    else
        warn "  ~ jpackage or dpkg-deb not available, skipping .deb creation"
    fi

    # --- Method B: Lightweight .deb (no JRE, uses system Java) ---
    task "  ▶ Building lightweight Linux .deb (no bundled JRE)..."
    build_lightweight_deb "$output_dir"

    # --- Method C: AppImage ---
    if command -v appimagetool &>/dev/null; then
        task "  ▶ Building AppImage..."
        build_appimage "$output_dir"
    else
        warn "  ~ appimagetool not available, skipping AppImage creation"
    fi

    # --- Method D: Portable tar.gz ---
    task "  ▶ Building portable Linux tar.gz..."
    build_portable_targz "$output_dir" "linux"

    log "✓ Linux packages ready in: $output_dir"
    ls -lh "$output_dir"/*.{deb,AppImage,tar.gz} 2>/dev/null || true
}

# ==============================================================
# Build lightweight .deb (no JRE bundled)
# ==============================================================
build_lightweight_deb() {
    local output_dir="$1"
    local pkg_dir="${TARGET_DIR}/deb-pkg/amereco-launcher_${VERSION}_amd64"
    local deb_output="${output_dir}/${ARTIFACT_ID}-${VERSION}-linux-x64.deb"

    mkdir -p "$pkg_dir/DEBIAN"
    mkdir -p "$pkg_dir/usr/share/amereco-launcher"
    mkdir -p "$pkg_dir/usr/share/applications"
    mkdir -p "$pkg_dir/usr/share/icons/hicolor/256x256/apps"
    mkdir -p "$pkg_dir/usr/bin"

    # Copy the JAR
    cp "${TARGET_DIR}/${ARTIFACT_ID}-${VERSION}.jar" "$pkg_dir/usr/share/amereco-launcher/"

    # Create control file
    cat > "$pkg_dir/DEBIAN/control" << DEBCTRL
Package: amereco-launcher
Version: ${VERSION}
Section: games
Priority: optional
Architecture: amd64
Depends: java-runtime (>= 16)
Maintainer: ${MAINTAINER}
Description: ${DESCRIPTION}
 A custom launcher for the Amereco Minecraft server.
 .
 Requires Java 16 or later (JavaFX is bundled inside the application JAR).
Homepage: ${URL}
DEBCTRL

    # Create desktop entry
    cat > "$pkg_dir/usr/share/applications/amereco-launcher.desktop" << DESKTOP
[Desktop Entry]
Name=Amereco Launcher
Comment=${DESCRIPTION}
Exec=/usr/bin/amereco-launcher
Icon=amereco-launcher
Terminal=false
Type=Application
Categories=Game;
StartupNotify=true
DESKTOP

    # Create launcher script
    cat > "$pkg_dir/usr/bin/amereco-launcher" << LAUNCHER
#!/bin/sh
exec /usr/lib/jvm/default/bin/java \\
    --add-reads ru.amereco.amerecolauncher=ALL-UNNAMED \\
    -jar /usr/share/amereco-launcher/${ARTIFACT_ID}-${VERSION}.jar "\$@"
LAUNCHER
    chmod +x "$pkg_dir/usr/bin/amereco-launcher"

    # Copy icon
    cp "$PROJECT_DIR/assets/AmerecoLauncher.png" "$pkg_dir/usr/share/icons/hicolor/256x256/apps/amereco-launcher.png"

    # Build .deb
    fakeroot dpkg-deb --build "$pkg_dir" "$deb_output" 2>&1
    rm -rf "${TARGET_DIR}/deb-pkg"
    log "  ✓ Lightweight .deb created: $(basename "$deb_output")"
}

# ==============================================================
# Build AppImage
# ==============================================================
build_appimage() {
    local output_dir="$1"
    local appdir="${TARGET_DIR}/AppDir"

    rm -rf "$appdir"
    mkdir -p "$appdir/usr/bin"
    mkdir -p "$appdir/usr/lib"
    mkdir -p "$appdir/usr/share/applications"
    mkdir -p "$appdir/usr/share/icons/hicolor/256x256/apps"

    # Copy the JAR
    cp "${TARGET_DIR}/${ARTIFACT_ID}-${VERSION}.jar" "$appdir/usr/lib/"

    # Create launcher
    cat > "$appdir/usr/bin/amereco-launcher" << 'LAUNCHER'
#!/bin/sh
APPDIR="$(dirname "$(dirname "$(readlink -f "$0")")")"
exec java --add-reads ru.amereco.amerecolauncher=ALL-UNNAMED \
    -jar "$APPDIR/usr/lib/AmerecoLauncher-1.0.0.jar" "$@"
LAUNCHER
    chmod +x "$appdir/usr/bin/amereco-launcher"

    # AppRun
    cat > "$appdir/AppRun" << 'APPRUN'
#!/bin/sh
APPDIR="$(dirname "$(readlink -f "$0")")"
exec "$APPDIR/usr/bin/amereco-launcher" "$@"
APPRUN
    chmod +x "$appdir/AppRun"

    # Desktop file for AppImage
    cat > "$appdir/usr/share/applications/amereco-launcher.desktop" << DESKTOP2
[Desktop Entry]
Name=Amereco Launcher
Exec=amereco-launcher
Icon=amereco-launcher
Type=Application
Categories=Game;
DESKTOP2

    # Copy desktop file to root
    cp "$appdir/usr/share/applications/amereco-launcher.desktop" "$appdir/"

    # Copy icon
    cp "$PROJECT_DIR/assets/AmerecoLauncher.png" "$appdir/usr/share/icons/hicolor/256x256/apps/amereco-launcher.png"

    # Symlink icon to root
    ln -sf "usr/share/icons/hicolor/256x256/apps/amereco-launcher.png" "$appdir/amereco-launcher.png"

    # Build AppImage
    ARCH=x86_64 appimagetool "$appdir" "${output_dir}/${ARTIFACT_ID}-${VERSION}-x86_64.AppImage"
    rm -rf "$appdir"
    log "  ✓ AppImage created"
}

# ==============================================================
# Step 4: Build Windows packages (from Linux)
# ==============================================================
build_windows_packages() {
    title
    task "Step 4: Building Windows packages"

    local output_dir="${PACKAGES_DIR}/windows"
    mkdir -p "$output_dir"

    # --- Option A: Portable ZIP ---
    task "  ▶ Building portable Windows ZIP..."
    local win_zip_dir="${TARGET_DIR}/win-pkg/AmerecoLauncher"
    mkdir -p "$win_zip_dir"

    # Copy the shade JAR
    cp "${TARGET_DIR}/${ARTIFACT_ID}-${VERSION}.jar" "$win_zip_dir/"

    # Create launcher batch file
    cat > "$win_zip_dir/AmerecoLauncher.bat" << 'WINBAT'
@echo off
title Amereco Launcher
java --add-reads ru.amereco.amerecolauncher=ALL-UNNAMED -jar AmerecoLauncher-1.0.0.jar
pause
WINBAT

    # Create a more robust PowerShell launcher
    cat > "$win_zip_dir/AmerecoLauncher.ps1" << 'WINPS1'
param(
    [string]$JavaPath = "java"
)
try {
    & $JavaPath --add-reads ru.amereco.amerecolauncher=ALL-UNNAMED -jar AmerecoLauncher-1.0.0.jar
} catch {
    Write-Host "Failed to launch: $_"
    Read-Host "Press Enter to exit"
}
WINPS1

    # Create ZIP
    cd "${TARGET_DIR}/win-pkg"
    zip -r "${output_dir}/${ARTIFACT_ID}-${VERSION}-windows-x64.zip" "AmerecoLauncher/" -q
    cd "$PROJECT_DIR"
    rm -rf "${TARGET_DIR}/win-pkg"

    # --- Option B: Self-contained EXE with bundled JRE (if jdk for windows available) ---
    if [[ -d "$TARGET_DIR/jdks/windows-x64" ]]; then
        task "  ▶ Building self-contained Windows directory (with JRE)..."
        local win_full_dir="${output_dir}/AmerecoLauncher-${VERSION}-windows-x64-full"
        mkdir -p "$win_full_dir"

        # Copy JRE
        cp -r "$TARGET_DIR/jdks/windows-x64"/* "$win_full_dir/"
        # Copy JAR
        cp "${TARGET_DIR}/${ARTIFACT_ID}-${VERSION}.jar" "$win_full_dir/"
        # Create launcher
        cat > "$win_full_dir/AmerecoLauncher.bat" << WINBAT2
@echo off
set DIR=%~dp0
"%DIR%bin\java.exe" --add-reads ru.amereco.amerecolauncher=ALL-UNNAMED -jar "%DIR%AmerecoLauncher-1.0.0.jar"
WINBAT2

        # Zip it
        cd "$output_dir"
        zip -r "${ARTIFACT_ID}-${VERSION}-windows-x64-with-jre.zip" "AmerecoLauncher-${VERSION}-windows-x64-full/" -q
        rm -rf "AmerecoLauncher-${VERSION}-windows-x64-full"
        cd "$PROJECT_DIR"
        log "  ✓ Self-contained Windows package created"
    fi

    log "✓ Windows packages ready in: $output_dir"
    ls -lh "$output_dir"/*.zip 2>/dev/null || true
}

# ==============================================================
# Step 5: Build macOS packages (from Linux)
# ==============================================================
build_macos_packages() {
    title
    task "Step 5: Building macOS packages"

    local output_dir="${PACKAGES_DIR}/macos"
    mkdir -p "$output_dir"

    # --- Option A: Portable ZIP for macOS ---
    task "  ▶ Building portable macOS ZIP..."
    local mac_zip_dir="${TARGET_DIR}/mac-pkg/AmerecoLauncher.app/Contents"
    mkdir -p "$mac_zip_dir/MacOS"
    mkdir -p "$mac_zip_dir/Resources"
    mkdir -p "${TARGET_DIR}/mac-pkg/AmerecoLauncher-dmg"

    # Copy the shade JAR
    cp "${TARGET_DIR}/${ARTIFACT_ID}-${VERSION}.jar" "$mac_zip_dir/Resources/"

    # Create Info.plist
    cat > "$mac_zip_dir/Info.plist" << MACPLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>AmerecoLauncher</string>
    <key>CFBundleIdentifier</key>
    <string>ru.amereco.amerecolauncher</string>
    <key>CFBundleName</key>
    <string>Amereco Launcher</string>
    <key>CFBundleVersion</key>
    <string>${VERSION}</string>
    <key>CFBundleShortVersionString</key>
    <string>${VERSION}</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>NSHighResolutionCapable</key>
    <true/>
</dict>
</plist>
MACPLIST

    # Create launcher script
    cat > "$mac_zip_dir/MacOS/AmerecoLauncher" << MACSH
#!/bin/bash
DIR="\$(cd "\$(dirname "\$0")/.." && pwd)"
exec java \\
    --add-reads ru.amereco.amerecolauncher=ALL-UNNAMED \\
    -jar "\$DIR/Resources/${ARTIFACT_ID}-${VERSION}.jar" "\$@"
MACSH
    chmod +x "$mac_zip_dir/MacOS/AmerecoLauncher"

    # Create symlink for a .command version (double-clickable in Finder)
    mkdir -p "${TARGET_DIR}/mac-pkg/AmerecoLauncher"
    ln -sf "Contents/MacOS/AmerecoLauncher" "${TARGET_DIR}/mac-pkg/AmerecoLauncher/AmerecoLauncher.command"

    # Create icon (use the PNG wrapped in a folder, macOS will use it)
    cp "$PROJECT_DIR/assets/AmerecoLauncher.png" "$mac_zip_dir/Resources/AmerecoLauncher.png"

    # Create ZIP
    cd "${TARGET_DIR}/mac-pkg"
    zip -r "${output_dir}/${ARTIFACT_ID}-${VERSION}-macos-x64.zip" "AmerecoLauncher.app" "AmerecoLauncher.command" -q

    # Also create a ZIP for ARM64 (same app, but with different JDK if bundled)
    cd "$PROJECT_DIR"

    # --- Option B: Self-contained with JRE ---
    for arch in "x64" "aarch64"; do
        local jdk_dir=""
        local arch_name=""
        if [[ "$arch" == "x64" ]]; then
            jdk_dir="$TARGET_DIR/jdks/mac-x64"
            arch_name="x64"
        else
            jdk_dir="$TARGET_DIR/jdks/mac-aarch64"
            arch_name="aarch64"
        fi

        if [[ -d "$jdk_dir" ]]; then
            task "  ▶ Building self-contained macOS ${arch} bundle..."
            local mac_full_dir="${output_dir}/AmerecoLauncher-${VERSION}-macos-${arch_name}"
            mkdir -p "$mac_full_dir/AmerecoLauncher.app/Contents/MacOS"
            mkdir -p "$mac_full_dir/AmerecoLauncher.app/Contents/Resources"
            mkdir -p "$mac_full_dir/AmerecoLauncher.app/Contents/Home"

            cp -r "$jdk_dir/"* "$mac_full_dir/AmerecoLauncher.app/Contents/Home/"
            cp "${TARGET_DIR}/${ARTIFACT_ID}-${VERSION}.jar" "$mac_full_dir/AmerecoLauncher.app/Contents/Resources/"

            # Info.plist with bundled JRE path
            cat > "$mac_full_dir/AmerecoLauncher.app/Contents/Info.plist" << MACPLIST2
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>AmerecoLauncher</string>
    <key>CFBundleIdentifier</key>
    <string>ru.amereco.amerecolauncher</string>
    <key>CFBundleName</key>
    <string>Amereco Launcher</string>
    <key>CFBundleVersion</key>
    <string>${VERSION}</string>
    <key>CFBundleShortVersionString</key>
    <string>${VERSION}</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>NSHighResolutionCapable</key>
    <true/>
</dict>
</plist>
MACPLIST2

            cat > "$mac_full_dir/AmerecoLauncher.app/Contents/MacOS/AmerecoLauncher" << MACSH2
#!/bin/bash
DIR="\$(cd "\$(dirname "\$0")/.." && pwd)"
exec "\$DIR/Home/bin/java" \\
    --add-reads ru.amereco.amerecolauncher=ALL-UNNAMED \\
    -jar "\$DIR/Resources/${ARTIFACT_ID}-${VERSION}.jar" "\$@"
MACSH2
            chmod +x "$mac_full_dir/AmerecoLauncher.app/Contents/MacOS/AmerecoLauncher"

            cd "$output_dir"
            zip -r "${ARTIFACT_ID}-${VERSION}-macos-${arch_name}-with-jre.zip" "AmerecoLauncher-${VERSION}-macos-${arch_name}/" -q
            rm -rf "AmerecoLauncher-${VERSION}-macos-${arch_name}"
            cd "$PROJECT_DIR"
        fi
    done

    # --- Option C: Create DMG using genisoimage ---
    if command -v genisoimage &>/dev/null; then
        task "  ▶ Building macOS DMG..."
        local dmg_dir="${TARGET_DIR}/dmg-build"
        mkdir -p "$dmg_dir"
        cp -r "${TARGET_DIR}/mac-pkg/AmerecoLauncher.app" "$dmg_dir/"
        # Create a symbolic link to /Applications for drag-and-drop install
        ln -s "/Applications" "$dmg_dir/Applications"

        genisoimage -V "Amereco Launcher" -D -R -apple -no-pad \
            -o "${output_dir}/${ARTIFACT_ID}-${VERSION}-macos-x64.dmg" \
            "$dmg_dir" 2>&1 | tail -3
        rm -rf "$dmg_dir"
        log "  ✓ DMG created"
    fi

    rm -rf "${TARGET_DIR}/mac-pkg" "${TARGET_DIR}/dmg-build"

    log "✓ macOS packages ready in: $output_dir"
    ls -lh "$output_dir"/*.{zip,dmg} 2>/dev/null || true
}

# ==============================================================
# Step 6: Build portable tar.gz for any platform
# ==============================================================
build_portable_targz() {
    local output_dir="$1"
    local platform="$2"
    
    local tgz_dir="${TARGET_DIR}/tgz-pkg/AmerecoLauncher-${VERSION}"
    mkdir -p "$tgz_dir"

    cp "${TARGET_DIR}/${ARTIFACT_ID}-${VERSION}.jar" "$tgz_dir/"

    # Create platform-specific launcher
    if [[ "$platform" == "linux" ]]; then
        cat > "$tgz_dir/AmerecoLauncher.sh" << LAUNCHSH
#!/bin/sh
DIR="\$(cd "\$(dirname "\$0")" && pwd)"
exec java \\
    --add-reads ru.amereco.amerecolauncher=ALL-UNNAMED \\
    -jar "\$DIR/${ARTIFACT_ID}-${VERSION}.jar" "\$@"
LAUNCHSH
        chmod +x "$tgz_dir/AmerecoLauncher.sh"
    fi

    # README
    cat > "$tgz_dir/README.txt" << README
Amereco Launcher v${VERSION}
============================
${DESCRIPTION}

REQUIREMENTS:
  - Java Runtime Environment (JRE) 16 or later

USAGE:
  Linux:   ./AmerecoLauncher.sh
  Windows: java --add-reads ru.amereco.amerecolauncher=ALL-UNNAMED -jar ${ARTIFACT_ID}-${VERSION}.jar
  macOS:   java --add-reads ru.amereco.amerecolauncher=ALL-UNNAMED -jar ${ARTIFACT_ID}-${VERSION}.jar

NOTE: JavaFX libraries are bundled inside the JAR for all platforms.
README

    cd "${TARGET_DIR}/tgz-pkg"
    tar -czf "${output_dir}/${ARTIFACT_ID}-${VERSION}-${platform}-x64-portable.tar.gz" \
        "AmerecoLauncher-${VERSION}/"
    cd "$PROJECT_DIR"
    rm -rf "${TARGET_DIR}/tgz-pkg"
    log "  ✓ Portable tar.gz created"
}

# ==============================================================
# Step 7: Generate checksums
# ==============================================================
generate_checksums() {
    title
    task "Step 7: Generating checksums"

    cd "$PACKAGES_DIR"
    if command -v sha256sum &>/dev/null; then
        find . -type f -exec sha256sum {} + > "sha256sums.txt"
        log "✓ SHA256 checksums generated"
    elif command -v shasum &>/dev/null; then
        find . -type f -exec shasum -a 256 {} + > "sha256sums.txt"
        log "✓ SHA256 checksums generated"
    fi
    cd "$PROJECT_DIR"
}

# ==============================================================
# Summary
# ==============================================================
print_summary() {
    title
    log "Build complete!"
    log "All packages are in: ${PACKAGES_DIR}"
    echo ""
    log "Generated packages:"
    echo "───────────────────────────────────────────────────"

    if [[ -d "$PACKAGES_DIR" ]]; then
        find "$PACKAGES_DIR" -type f | sort | while read -r pkg; do
            local size
            size=$(du -h "$pkg" | cut -f1)
            echo "  $(basename "$pkg")  (${size})"
        done
    fi

    echo ""
    echo "───────────────────────────────────────────────────"
    echo ""
    log "Usage notes:"
    echo "  • Linux .deb:      sudo dpkg -i <file>.deb"
    echo "  • Linux AppImage:  chmod +x <file>.AppImage && ./<file>.AppImage"
    echo "  • Linux portable:  tar -xzf <file>.tar.gz && ./AmerecoLauncher.sh"
    echo "  • Windows:         Extract ZIP and run AmerecoLauncher.bat"
    echo "  • macOS:           Open .dmg or extract ZIP, run AmerecoLauncher.app"
    echo ""
    log "Requirements:"
    echo "  • Java 16+ runtime is required (NOT bundled in lightweight packages)"
    echo "  • Full packages with bundled JRE are self-contained"
}

# ==============================================================
# Main execution
# ==============================================================
main() {
    MISSING_TOOLS=()
    local BUILD_LINUX=true
    local BUILD_WINDOWS=true
    local BUILD_MACOS=true
    local DOWNLOAD_JDKS=false
    local SKIP_SHADE=false

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --linux-only) BUILD_WINDOWS=false; BUILD_MACOS=false; shift ;;
            --win-only)   BUILD_LINUX=false; BUILD_MACOS=false; shift ;;
            --mac-only)   BUILD_LINUX=false; BUILD_WINDOWS=false; shift ;;
            --download-jdks) DOWNLOAD_JDKS=true; shift ;;
            --skip-shade) SKIP_SHADE=true; shift ;;
            --help|-h)
                echo "Usage: $0 [options]"
                echo ""
                echo "Options:"
                echo "  --linux-only     Build only Linux packages"
                echo "  --win-only       Build only Windows packages"
                echo "  --mac-only       Build only macOS packages"
                echo "  --download-jdks  Download JDKs for all platforms (for self-contained bundles)"
                echo "  --skip-shade     Skip rebuilding the shade JAR"
                echo "  --help, -h       Show this help"
                exit 0
                ;;
            *)
                err "Unknown option: $1"
                echo "Usage: $0 [options]"
                exit 1
                ;;
        esac
    done

    echo ""
    echo "  ╔═══════════════════════════════════════════════════════╗"
    echo "  ║        Amereco Launcher — Cross-Platform Build        ║"
    echo "  ║        Packaging from Linux for ALL platforms         ║"
    echo "  ╚═══════════════════════════════════════════════════════╝"

    check_prerequisites

    if [[ "$SKIP_SHADE" == false ]]; then
        build_shade_jar
    else
        log "Skipping shade JAR build (--skip-shade)"
    fi

    # Download JDKs if requested (needed for self-contained bundles)
    if [[ "$DOWNLOAD_JDKS" == true ]]; then
        download_jdks
    fi

    mkdir -p "$PACKAGES_DIR"

    if [[ "$BUILD_LINUX" == true ]]; then
        build_linux_packages
    fi

    if [[ "$BUILD_WINDOWS" == true ]]; then
        build_windows_packages
    fi

    if [[ "$BUILD_MACOS" == true ]]; then
        build_macos_packages
    fi

    generate_checksums
    print_summary
}

main "$@"
