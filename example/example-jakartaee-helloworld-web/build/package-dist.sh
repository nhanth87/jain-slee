#!/usr/bin/env bash
# Directory distribution (Digicom-ET standard) — NEVER a WAR.
#
#   dist/<app>/
#     run.sh
#     <app>.jar                 # thin app jar (Main-Class)
#     lib/                      # runtime jars
#     html/                     # UI ONLY — *.html *.js *.css (no jars)
#     configs/log4j2.xml
#     logs/                     # created empty
#
# Source of truth for UI: repo html/ → packaged into dist/html/
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${EXAMPLE_DIR}"

ARTIFACT_ID="example-jakartaee-helloworld-web"
VERSION="1.0.0-SNAPSHOT"
APP_NAME="${APP_NAME:-helloworld-jakartaee-jainslee}"
DIST_ROOT="${DIST_ROOT:-${EXAMPLE_DIR}/dist}"
DIST_DIR="${DIST_ROOT}/${APP_NAME}"
PACKAGED_JAR="target/${ARTIFACT_ID}.jar"

# JDK 25 only
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  for cand in \
    "${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0" \
    "${HOME}/.local/share/mise/installs/java/zulu-25" \
    "${HOME}/.local/share/mise/installs/java/25"; do
    if [[ -x "${cand}/bin/java" ]]; then
      export JAVA_HOME="${cand}"
      break
    fi
  done
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "error: JDK 25 required. Set JAVA_HOME to zulu-25 (mise)." >&2
  exit 1
fi
export PATH="${JAVA_HOME}/bin:${PATH}"

if [[ ! -d html ]]; then
  echo "error: missing html/ (UI source — *.html *.js *.css)" >&2
  exit 1
fi

echo "Packaging thin jar …"
mvn -B -ntp package -DskipTests

if [[ ! -f "${PACKAGED_JAR}" ]]; then
  # maven-jar-plugin may emit versioned name depending on finalName
  if [[ -f "target/${ARTIFACT_ID}-${VERSION}.jar" ]]; then
    PACKAGED_JAR="target/${ARTIFACT_ID}-${VERSION}.jar"
  else
    echo "error: missing app jar under target/ after mvn package" >&2
    ls -la target/*.jar 2>/dev/null || true
    exit 1
  fi
fi

echo "Copying runtime dependencies …"
mvn -B -ntp dependency:copy-dependencies \
  -DincludeScope=runtime \
  -DoutputDirectory=target/lib \
  -DoverWriteReleases=false \
  -DoverWriteSnapshots=true

rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}/lib" "${DIST_DIR}/html" "${DIST_DIR}/configs" "${DIST_DIR}/logs"
cp -a target/lib/. "${DIST_DIR}/lib/"
cp -a "${PACKAGED_JAR}" "${DIST_DIR}/${APP_NAME}.jar"
cp -a html/. "${DIST_DIR}/html/"
if [[ -f src/main/resources/log4j2.xml ]]; then
  cp -a src/main/resources/log4j2.xml "${DIST_DIR}/configs/log4j2.xml"
fi

# Fail closed: no jars under html/
if find "${DIST_DIR}/html" -name '*.jar' | grep -q .; then
  echo "error: jars under html/ — UI must be HTML/CSS/JS only" >&2
  exit 1
fi

cat > "${DIST_DIR}/run.sh" <<EOF
#!/usr/bin/env bash
# Launch ${APP_NAME} from directory dist (Log4j2 → logs/).
set -euo pipefail
ROOT="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
cd "\${ROOT}"

if [[ -z "\${JAVA_HOME:-}" || ! -x "\${JAVA_HOME}/bin/java" ]]; then
  for cand in \\
    "\${HOME}/.local/share/mise/installs/java/zulu-25.34.17.0" \\
    "\${HOME}/.local/share/mise/installs/java/zulu-25" \\
    "\${HOME}/.local/share/mise/installs/java/25"; do
    if [[ -x "\${cand}/bin/java" ]]; then
      export JAVA_HOME="\${cand}"
      break
    fi
  done
fi
if [[ -z "\${JAVA_HOME:-}" || ! -x "\${JAVA_HOME}/bin/java" ]]; then
  echo "error: JDK 25 required" >&2
  exit 1
fi

CP="${APP_NAME}.jar"
for j in lib/*.jar; do
  CP="\${CP}:\${j}"
done

mkdir -p logs
echo "JAVA_HOME=\${JAVA_HOME}"
echo "HTML  \${ROOT}/html/   (open index.html or: python -m http.server -d html 8080)"
echo "RA    http://127.0.0.1:8081/hello"
echo
exec "\${JAVA_HOME}/bin/java" \\
  \${JAVA_OPTS:-} \\
  -Dlog4j2.configurationFile="\${ROOT}/configs/log4j2.xml" \\
  -Dhello.html.dir="\${ROOT}/html" \\
  -cp "\${CP}" \\
  com.example.helloworld.jakartaee.HelloWorldMain \\
  "\$@"
EOF
chmod +x "${DIST_DIR}/run.sh"

LIB_COUNT="$(find "${DIST_DIR}/lib" -name '*.jar' | wc -l | tr -d ' ')"
echo
echo "Distribution ready (directory layout — not WAR):"
echo "  ${DIST_DIR}/"
echo "  ├── run.sh"
echo "  ├── ${APP_NAME}.jar"
echo "  ├── html/     (UI only)"
echo "  ├── configs/log4j2.xml"
echo "  ├── logs/"
echo "  └── lib/      (${LIB_COUNT} jars)"
echo
echo "Run:  ${DIST_DIR}/run.sh"
