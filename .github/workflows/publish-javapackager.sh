#!/usr/bin/env bash
# publish-javapackager.sh
# Builds a pinned commit of JavaPackager (devel branch) and deploys it
# to GitHub Packages (maven.pkg.github.com).
#
# Usage:
#   ./publish-javapackager.sh <commit-sha> <github-token> [github-actor]
#
# Arguments:
#   commit-sha     Full or short SHA from the fvarrui/JavaPackager devel branch
#   github-token   Personal access token or GITHUB_TOKEN with packages:write scope
#   github-actor   GitHub username used for Maven authentication (default: $USER)
#
# Requirements (checked at startup):
#   - git
#   - java  (17+)
#   - mvn   (Maven 3.x)
#   - python3
#   - curl

set -euo pipefail

# ─── Colour helpers ───────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
die()     { echo -e "${RED}[ERROR]${RESET} $*" >&2; exit 1; }
section() { echo -e "\n${BOLD}=== $* ===${RESET}"; }

# ─── Arguments ────────────────────────────────────────────────────────────────
COMMIT="${1:-}"
GITHUB_TOKEN="${2:-${GITHUB_TOKEN:-}}"
GITHUB_ACTOR="${3:-${GITHUB_ACTOR:-${USER}}}"

[[ -z "$COMMIT" ]]       && die "Missing argument: commit SHA\n  Usage: $0 <commit-sha> <github-token> [github-actor]"
[[ -z "$GITHUB_TOKEN" ]] && die "Missing argument: github-token (or set \$GITHUB_TOKEN)"

# ─── Dependency checks ────────────────────────────────────────────────────────
section "Checking dependencies"

check_cmd() {
    local cmd="$1" hint="${2:-}"
    if command -v "$cmd" &>/dev/null; then
        success "$cmd found at $(command -v "$cmd")"
    else
        die "$cmd not found.${hint:+ $hint}"
    fi
}

check_cmd git     "Install git from your package manager"
check_cmd mvn     "Install Maven 3.x: https://maven.apache.org/download.cgi"
check_cmd python3 "Install Python 3"
check_cmd curl    "Install curl from your package manager"

# Java check with version gate
check_cmd java "Install JDK 17+: https://adoptium.net"
JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
[[ "$JAVA_VER" -ge 17 ]] 2>/dev/null \
    || die "Java 17+ required, found version: $(java -version 2>&1 | head -1)"
success "Java version OK (${JAVA_VER})"

# ─── Derived variables ────────────────────────────────────────────────────────
SHA="${COMMIT:0:7}"
VERSION="1.7.7-${SHA}"
ORG="Merkel-Games"
PACKAGE_NAME="io.github.fvarrui.javapackager"
MAVEN_REPO_URL="https://maven.pkg.github.com/${ORG}/AmerecoLauncher"
M2_BASE="${HOME}/.m2/repository/io/github/fvarrui/javapackager/${VERSION}"
WORK_DIR="$(mktemp -d)/JavaPackager"

info "Commit : ${COMMIT}"
info "Version: ${VERSION}"
info "Target : ${MAVEN_REPO_URL}"

# ─── Step 1: Check if version already exists in GitHub Packages ───────────────
section "Checking GitHub Packages for ${VERSION}"

API_URL="https://api.github.com/orgs/${ORG}/packages/maven/${PACKAGE_NAME}/versions"
HTTP_CODE=$(curl -s -o /tmp/jp_versions.json -w "%{http_code}" \
    -H "Authorization: token ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    "$API_URL")

if [[ "$HTTP_CODE" == "200" ]]; then
    if grep -q "\"name\": \"${VERSION}\"" /tmp/jp_versions.json; then
        success "Version ${VERSION} already exists in GitHub Packages — nothing to do."
        exit 0
    else
        info "Version ${VERSION} not found in GitHub Packages, proceeding with build."
    fi
else
    warn "GitHub Packages API returned HTTP ${HTTP_CODE} — assuming version absent, proceeding."
fi

# ─── Step 2: Clone and checkout pinned commit ─────────────────────────────────
section "Cloning JavaPackager @ ${COMMIT}"

mkdir -p "$(dirname "$WORK_DIR")"
git clone https://github.com/fvarrui/JavaPackager.git "$WORK_DIR"
cd "$WORK_DIR"
git fetch origin devel
git checkout "$COMMIT"
success "Checked out $(git log --oneline -1)"

# ─── Step 3: Patch sources ────────────────────────────────────────────────────
section "Patching JavaPackager source"

# Fix 1: override the project version in build.gradle
# Using -Pversion alone does NOT propagate into the embedded Maven subprocess
# (generatePluginDescriptor), so we patch the file directly.
sed -i "s/^version = '.*'/version = '${VERSION}'/" build.gradle
info "Fix 1: version set to '${VERSION}'"

# Fix 2: upgrade commons-lang3 from 3.9 to 3.12.0
# ObjectUtils.getIfNull(Object, Supplier) was added in 3.12.0;
# the devel branch calls it but declares 3.9, causing NoSuchMethodError at runtime.
sed -i "s/commons-lang3:[0-9.]*/commons-lang3:3.12.0/" build.gradle
info "Fix 2: commons-lang3 bumped to 3.12.0"

echo "--- build.gradle version and commons-lang3 lines ---"
grep "^version = \|commons-lang3" build.gradle

# Fix 3: Velocity 2.3 treats '@' as a directive sigil, causing:
#   Lexical error, Encountered "@" at linux/startup.sh.vtl[line N, column M]
# The template has 6 lines containing bash array syntax with [@] or $@:
#   lines 31,35,39 — for loop array bounds: ${#v1[@]}
#   line  70       — JVMOptionsFile read loop: ${option[@]}
#   lines 92,94    — java invocation: ${JVMDefaultOptions[@]} $@
# Strategy: iterate every line, skip Velocity directives (start with #),
# wrap any line containing a bare '@' in #[[...]]# (unparsed content block).
# This is robust to line number shifts from upstream edits.
python3 - << 'PYEOF'
path = "src/main/resources/linux/startup.sh.vtl"
with open(path, "r") as f:
    lines = f.readlines()
patched_lines = []
count = 0
for i, line in enumerate(lines, start=1):
    stripped = line.lstrip()
    # Leave Velocity directives (#if, #else, #foreach, #end ...) untouched.
    # Shebang (#!/) is not a directive — but it has no @ so it won't be touched anyway.
    is_velocity_directive = stripped.startswith('#') and not stripped.startswith('#!')
    if '@' in line and not is_velocity_directive:
        patched_lines.append('#[[ ' + line.rstrip('\n') + ' ]]#\n')
        count += 1
    else:
        patched_lines.append(line)
with open(path, "w") as f:
    f.writelines(patched_lines)
print(f"Fix 3: wrapped {count} line(s) containing '@' in {path}")
PYEOF

echo "--- startup.sh.vtl line 70 ---"
sed -n '70p' src/main/resources/linux/startup.sh.vtl

# ─── Step 4: Build and install to local Maven repo ────────────────────────────
section "Building JavaPackager ${VERSION}"

./gradlew publishToMavenLocal
success "Gradle build complete"

# ─── Step 5: Verify artifacts ─────────────────────────────────────────────────
section "Verifying built artifacts"

echo "Artifact directory: ${M2_BASE}"
ls -la "${M2_BASE}"

[[ -f "${M2_BASE}/javapackager-${VERSION}.jar" ]] \
    || die "JAR not found: ${M2_BASE}/javapackager-${VERSION}.jar"
[[ -f "${M2_BASE}/javapackager-${VERSION}.pom" ]] \
    || die "POM not found: ${M2_BASE}/javapackager-${VERSION}.pom"

success "All required artifacts found"

# ─── Step 6: Configure Maven settings ────────────────────────────────────────
section "Configuring Maven settings.xml"

# Write credentials into settings.xml so Maven can authenticate to GitHub Packages.
# NOTE: heredoc without quotes (EOF, not 'EOF') so bash expands the variables.
# The resulting XML must contain the literal token value — NOT ${env.GITHUB_TOKEN}
# Maven-style placeholders, which only work when the env vars are exported into
# the Maven JVM process (as they are in GitHub Actions but not in a local shell).
SETTINGS_FILE="${HOME}/.m2/settings.xml"
mkdir -p "${HOME}/.m2"

# Back up any existing settings so we don't lose local repo configs
if [[ -f "$SETTINGS_FILE" ]]; then
    cp "$SETTINGS_FILE" "${SETTINGS_FILE}.bak"
    warn "Existing settings.xml backed up to ${SETTINGS_FILE}.bak"
fi

cat > "$SETTINGS_FILE" << EOF
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>${GITHUB_ACTOR}</username>
      <password>${GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
EOF

# Verify the token was actually written (not a literal placeholder)
if grep -q 'env.GITHUB_TOKEN' "$SETTINGS_FILE"; then
    die "settings.xml still contains literal placeholder — heredoc expansion failed"
fi
success "settings.xml written with credentials for actor '${GITHUB_ACTOR}'"

# ─── Step 7: Deploy to GitHub Packages ───────────────────────────────────────
section "Deploying ${VERSION} to GitHub Packages"

# Maven's deploy:deploy-file refuses to deploy a file that already lives inside
# the local .m2 repository (MojoFailureException: "Cannot deploy artifact from
# the local repository"). Copy to a temp dir outside .m2 first.
DEPLOY_DIR=$(mktemp -d)
cp "${M2_BASE}/javapackager-${VERSION}.jar" "${DEPLOY_DIR}/"
cp "${M2_BASE}/javapackager-${VERSION}.pom" "${DEPLOY_DIR}/"
info "Artifacts copied to ${DEPLOY_DIR}"

# Credentials are passed both via settings.xml (repositoryId=github) AND via
# -Dusername/-Dpassword as an explicit fallback, in case Maven resolves a
# different settings.xml (e.g. from MAVEN_CONFIG or wrapper config).
mvn --batch-mode deploy:deploy-file \
    -DgroupId=io.github.fvarrui \
    -DartifactId=javapackager \
    -Dversion="${VERSION}" \
    -Dpackaging=jar \
    -Dfile="${DEPLOY_DIR}/javapackager-${VERSION}.jar" \
    -DpomFile="${DEPLOY_DIR}/javapackager-${VERSION}.pom" \
    -DrepositoryId=github \
    -Durl="${MAVEN_REPO_URL}" \
    -Dusername="${GITHUB_ACTOR}" \
    -Dpassword="${GITHUB_TOKEN}"

success "Successfully deployed javapackager:${VERSION} to ${MAVEN_REPO_URL}"