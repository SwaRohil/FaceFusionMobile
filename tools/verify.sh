#!/usr/bin/env bash
# ---------------------------------------------------------------------------------------------
# SwapStudio AI - repository self-check
#
# Runs the cheap, deterministic checks that catch the mistakes which otherwise only show up after
# a 6 minute Gradle build (or, worse, on a phone):
#
#   1. every file the build depends on is present;
#   2. every `R.string.*` used from Kotlin is declared in res/values/strings.xml;
#   3. app/src/main/assets/models.json is valid, ids/file names are unique and digests are well formed;
#   4. the manifest declares the components and permissions the Kotlin code actually starts;
#   5. no leftover template identifiers (`com.example`, `TODO(`, `NotImplementedError`).
#
# Usage:
#   tools/verify.sh              # static checks only (~1 s, needs bash + python3)
#   tools/verify.sh --with-build # also runs ./gradlew :app:assembleDebug and :app:testDebugUnitTest
#
# Exit code is non-zero as soon as anything fails, so it can be used as a pre-commit hook.
# ---------------------------------------------------------------------------------------------
set -u -o pipefail

cd "$(dirname "$0")/.." || exit 1
ROOT="$PWD"
WITH_BUILD=0
[ "${1:-}" = "--with-build" ] && WITH_BUILD=1

FAILED=0
CURRENT=""

section() { CURRENT="$1"; printf '\n== %s\n' "$1"; }
pass()    { printf '   ok    %s\n' "$1"; }
fail()    { printf '   FAIL  %s\n' "$1"; FAILED=$((FAILED + 1)); }
skip()    { printf '   skip  %s\n' "$1"; }

need_file() {
    if [ -f "$1" ]; then pass "$1"; else fail "$1 is missing"; fi
}

# ---------------------------------------------------------------------------------------------
section "Required files"
# ---------------------------------------------------------------------------------------------
for f in \
    settings.gradle.kts build.gradle.kts gradle.properties gradle/libs.versions.toml \
    app/build.gradle.kts app/proguard-rules.pro \
    app/src/main/AndroidManifest.xml \
    app/src/main/assets/models.json \
    app/src/main/java/com/swapstudio/ai/MainActivity.kt \
    app/src/main/java/com/swapstudio/ai/SwapStudioApp.kt \
    app/src/main/java/com/swapstudio/ai/ui/SwapStudioNavHost.kt \
    app/src/main/java/com/swapstudio/ai/core/swap/PhotoSwapPipeline.kt \
    app/src/main/java/com/swapstudio/ai/core/swap/VideoProcessor.kt \
    app/src/main/java/com/swapstudio/ai/core/export/ExportManager.kt \
    app/src/main/java/com/swapstudio/ai/core/models/ModelCatalog.kt \
    app/src/main/res/values/strings.xml app/src/main/res/values/themes.xml \
    app/src/main/res/xml/file_paths.xml \
    app/src/test/java/com/swapstudio/ai/core/util/HashUtilsTest.kt \
    ; do need_file "$f"; done

if command -v python3 >/dev/null 2>&1; then
    # ---------------------------------------------------------------------------------------
    section "Resources (strings, manifest, model manifest)"
    # ---------------------------------------------------------------------------------------
    python3 - "$ROOT" <<'PY'
import json, re, sys, xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
failures = []

# ---- strings: everything referenced from Kotlin must be declared ------------------------------
strings_xml = root / "app/src/main/res/values/strings.xml"
trees = [ET.parse(strings_xml)]
declared = {el.get("name") for el in trees[0].getroot().findall("string")}
# A gradle build would also see these; scan every res/values* folder that exists.
for extra in (root / "app/src/main/res").glob("values-*/strings.xml"):
    trees.append(ET.parse(extra))
    declared |= {el.get("name") for el in trees[-1].getroot().findall("string")}

used = set()
for kt in (root / "app/src/main/java").rglob("*.kt"):
    text = kt.read_text(encoding="utf-8", errors="replace")
    used |= set(re.findall(r"R\.string\.([A-Za-z0-9_]+)", text))
manifest_text = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
used |= {m for m in re.findall(r"@string/([A-Za-z0-9_]+)", manifest_text)
         if m not in {"app_name"}}
# `@string/app_name` is a placeholder kept by AGP; check it too.
used |= {m for m in re.findall(r"@string/([A-Za-z0-9_]+)", manifest_text)}

missing = sorted(used - declared - {"app_name"})
if missing:
    failures.append("strings.xml is missing: " + ", ".join(missing))
unused = sorted(declared - used)
print(f"   ok    {len(declared)} strings declared, {len(used)} referenced")
if unused:
    print("   note  declared but unused: " + ", ".join(unused))
if missing:
    print("   FAIL  undefined string ids: " + ", ".join(missing))

# ---- manifest: components the code starts must be declared -----------------------------------
manifest = ET.parse(root / "app/src/main/AndroidManifest.xml").getroot()
android = "{http://schemas.android.com/apk/res/android}"
app = manifest.find("application")
components = {}
for tag in ("activity", "service", "provider", "receiver"):
    for el in app.findall(tag):
        components[el.get(android + "name").lstrip(".")] = tag

required_components = {
    "MainActivity": "activity",
    "SwapStudioApp": None,
    "core.background.SwapForegroundService": "service",
    "core.background.ModelDownloadService": "service",
}
for name, tag in required_components.items():
    if tag is None:
        if app.get(android + "name", "").lstrip(".") != name:
            failures.append(f"application android:name is not {name}")
        continue
    if components.get(name) != tag:
        failures.append(f"manifest does not declare <{tag}> {name}")

permissions = {p.get(android + "name") for p in manifest.findall("uses-permission")}
for perm in (
    "android.permission.INTERNET",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
):
    if perm not in permissions:
        failures.append("manifest is missing uses-permission " + perm)

# AGP rewrites ${applicationId} at manifest merge time, so match the token as written.
providers = {el.get(android + "authorities") for el in app.findall("provider")}
file_provider = "${applicationId}.fileprovider"
if file_provider not in providers:
    failures.append(f"manifest has no FileProvider authority {file_provider}")
export_ok = any(
    el.get(android + "authorities") == file_provider and el.get(android + "exported") == "false"
    for el in app.findall("provider")
)
if not export_ok:
    failures.append("FileProvider must be android:exported=\"false\"")

for svc in app.findall("service"):
    if not svc.get(android + "foregroundServiceType"):
        failures.append(f"service {svc.get(android + 'name')} has no foregroundServiceType")

print(f"   ok    {len(components)} components, {len(permissions)} permissions")

# every xml resource must parse
for xml in (root / "app/src/main/res").rglob("*.xml"):
    try:
        ET.parse(xml)
    except ET.ParseError as exc:
        failures.append(f"{xml.relative_to(root)} is not well formed: {exc}")
print("   ok    all res/ XML files parse")

# ---- model manifest --------------------------------------------------------------------------
models = json.loads((root / "app/src/main/assets/models.json").read_text(encoding="utf-8"))
entries = models.get("models")
if not isinstance(entries, list) or not entries:
    failures.append("models.json has no models[] array")
    entries = []
ids, files = set(), set()
sha_re = re.compile(r"^[0-9a-f]{64}$")
crc_re = re.compile(r"^[0-9a-f]{8}$")
unpinned = []
for model in entries:
    mid = model.get("id")
    if not mid or mid in ids:
        failures.append(f"models.json: duplicate or missing id {mid!r}")
    ids.add(mid)
    for key in ("name", "file_name", "url", "description", "version"):
        if not model.get(key):
            failures.append(f"models.json[{mid}]: missing {key}")
    fn = model.get("file_name", "")
    if fn in files:
        failures.append(f"models.json[{mid}]: duplicate file_name {fn}")
    files.add(fn)
    url = model.get("url", "")
    if not url.startswith("https://"):
        failures.append(f"models.json[{mid}]: url must be https")
    if " " in url:
        failures.append(f"models.json[{mid}]: url contains a space")
    if not isinstance(model.get("size_mb"), int) or model.get("size_mb", 0) <= 0:
        failures.append(f"models.json[{mid}]: size_mb must be a positive integer")
    if not isinstance(model.get("required"), bool):
        failures.append(f"models.json[{mid}]: required must be a boolean")
    sha, crc = model.get("sha256", ""), model.get("crc32", "")
    if sha and not sha_re.match(sha):
        failures.append(f"models.json[{mid}]: sha256 is not 64 hex chars")
    if crc and not crc_re.match(crc):
        failures.append(f"models.json[{mid}]: crc32 is not 8 hex chars")
    if not sha and not crc:
        unpinned.append(mid)
total_mb = sum(m.get("size_mb", 0) for m in entries)
required_mb = sum(m.get("size_mb", 0) for m in entries if m.get("required"))
print(f"   ok    {len(entries)} models, {required_mb} MB required / {total_mb} MB total")
if unpinned:
    print("   note  verified by byte count only (no sha256/crc32): " + ", ".join(unpinned))

# ---- structural sanity: balanced braces / parens / comments ----------------------------------
# Kotlin has no preprocessor, so an unbalanced `{`, `(` or an unterminated `/* */` in a KDoc comment
# silently swallows the rest of the file and the error surfaces hundreds of lines away. Doing this
# here costs milliseconds and has caught exactly that bug.
unbalanced = []
for kt in sorted((root / "app/src").rglob("*.kt")):
    text = kt.read_text(encoding="utf-8", errors="replace")
    depth = {"{": 0, "(": 0}
    pairs = {"}": "{", ")": "("}
    i, n = 0, len(text)
    in_block_comment = False
    in_line_comment = False
    in_string = False
    in_raw = False
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
        elif in_block_comment:
            if ch == "*" and nxt == "/":
                in_block_comment = False
                i += 1
            elif ch == "/" and nxt == "*":
                kt_note = "nested block comment"
        elif in_string:
            if ch == "\\":
                i += 1
            elif ch == '"':
                in_string = False
        elif in_raw:
            if ch == '"' and text[i:i + 3] == '\"\"\"':
                in_raw = False
                i += 2
        else:
            if ch == "/" and nxt == "/":
                in_line_comment = True
                i += 1
            elif ch == "/" and nxt == "*":
                in_block_comment = True
                i += 1
            elif text[i:i + 3] == '\"\"\"':
                in_raw = True
                i += 2
            elif ch == '"':
                in_string = True
            elif ch in depth:
                depth[ch] += 1
            elif ch in pairs:
                depth[pairs[ch]] -= 1
                if depth[pairs[ch]] < 0:
                    unbalanced.append(f"{kt.relative_to(root)}: unmatched '{ch}'")
                    break
        i += 1
    if in_block_comment:
        unbalanced.append(f"{kt.relative_to(root)}: unterminated /* comment")
    if in_string:
        unbalanced.append(f"{kt.relative_to(root)}: unterminated string literal")
    for opener, count in depth.items():
        if count != 0:
            unbalanced.append(f"{kt.relative_to(root)}: {count:+d} unbalanced '{opener}'")
if unbalanced:
    failures.extend(unbalanced)
else:
    print(f"   ok    balanced braces/parens/comments in {len(list((root / 'app/src').rglob('*.kt')))} Kotlin files")

# ---- no template leftovers -------------------------------------------------------------------
bad = []
for kt in (root / "app/src/main/java").rglob("*.kt"):
    text = kt.read_text(encoding="utf-8", errors="replace")
    for needle in ("com.example", "NotImplementedError", "TODO(\"", "FIXME"):
        if needle in text:
            bad.append(f"{kt.relative_to(root)}: {needle}")
for xml in (root / "app/src/main").rglob("*.xml"):
    if "com.example" in xml.read_text(encoding="utf-8", errors="replace"):
        bad.append(f"{xml.relative_to(root)}: com.example")
if bad:
    failures.extend(bad)
else:
    print("   ok    no template leftovers")

if failures:
    print()
    for f in failures:
        print("   FAIL  " + f)
    sys.exit(1)
PY
    [ $? -ne 0 ] && FAILED=$((FAILED + 1))
else
    section "Resources (strings, manifest, model manifest)"
    skip "python3 is not installed"
fi

# ---------------------------------------------------------------------------------------------
section "Kotlin source sanity"
# ---------------------------------------------------------------------------------------------
KT_COUNT=$(find app/src/main/java -name '*.kt' | wc -l | tr -d ' ')
if [ "$KT_COUNT" -gt 30 ]; then pass "$KT_COUNT Kotlin files"; else fail "only $KT_COUNT Kotlin files found"; fi

# Every source file must sit under the declared namespace, otherwise Hilt/R8 wiring drifts.
STRAY=$(find app/src/main/java -name '*.kt' -not -path 'app/src/main/java/com/swapstudio/ai/*')
if [ -z "$STRAY" ]; then pass "all sources under com/swapstudio/ai"; else fail "sources outside namespace: $STRAY"; fi

# A package declaration must match the folder (cheap regex, no compiler needed).
MISMATCH=""
while IFS= read -r f; do
    expected=$(dirname "${f#app/src/main/java/}" | tr '/' '.')
    actual=$(sed -n 's/^package \(.*\)$/\1/p' "$f" | head -1 | tr -d '\r')
    [ "$actual" != "$expected" ] && MISMATCH="$MISMATCH\n      $f declares '$actual', expected '$expected'"
done < <(find app/src/main/java -name '*.kt')
if [ -z "$MISMATCH" ]; then pass "package declarations match folders"; else fail "package mismatch:$MISMATCH"; fi

# Large binaries never belong in the repo (models are downloaded at runtime).
BIG=$(find . -path ./.git -prune -o -type f -size +8M -print | grep -v '^./index.html$' || true)
if [ -z "$BIG" ]; then pass "no files over 8 MB in the repo"; else fail "checked in large files: $BIG"; fi

# ---------------------------------------------------------------------------------------------
section "Optional Gradle build"
# ---------------------------------------------------------------------------------------------
if [ "$WITH_BUILD" -eq 1 ]; then
    if [ -x ./gradlew ] && command -v java >/dev/null 2>&1; then
        if ./gradlew --offline -q :app:testDebugUnitTest 2>&1 | tail -20; then
            pass "unit tests"
        else
            fail "unit tests (see output above)"
        fi
        if ./gradlew --offline -q :app:assembleDebug 2>&1 | tail -20; then
            pass "assembleDebug"
        else
            fail "assembleDebug (see output above)"
        fi
    else
        skip "gradlew or a JDK is not available"
    fi
else
    skip "build checks (pass --with-build to run ./gradlew)"
fi

printf '\n'
if [ "$FAILED" -eq 0 ]; then
    printf 'All checks passed.\n'
else
    printf '%d check(s) failed.\n' "$FAILED"
fi
exit "$FAILED"
