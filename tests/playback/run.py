#!/usr/bin/env python3
"""Run the real stream request/parser code on a JVM with test-only Android stubs.

Requires Python 3 and JDK 17+. Downloads pinned protobuf/JSON dependencies from
Maven Central into build/. No Android SDK, GitHub credentials, or live YouTube
requests are needed. Android UI, bytecode injection and timing are not covered.
"""

from pathlib import Path
import os
import platform
import shutil
import subprocess
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build/playback-tests"
PROTO_VERSION = "4.34.0"
MAVEN = "https://repo.maven.apache.org/maven2"

# Only platform/application services are stubbed. ClientType, PlayerRoutes,
# Requester, StreamingDataRequest and both protobuf schemas are production code.
STUBS = {
    "android/os/Build.java": """
package android.os;
public final class Build {
    public static final String MANUFACTURER = "Test", MODEL = "Test", ID = "Test";
    public static final class VERSION {
        public static final String RELEASE = "15";
        public static final int SDK_INT = 35;
    }
}
""",
    "androidx/annotation/NonNull.java": "package androidx.annotation; public @interface NonNull {}",
    "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}",
    "app/revanced/extension/shared/Logger.java": """
package app.revanced.extension.shared;
import java.util.function.Supplier;
public final class Logger {
    public static void printDebug(Supplier<String> message) {}
    public static void printInfo(Supplier<String> message, Exception ex) {}
    public static void printException(Supplier<String> message, Exception ex) {}
}
""",
    "app/revanced/extension/shared/Utils.java": """
package app.revanced.extension.shared;
import java.util.*;
import java.util.concurrent.*;
public final class Utils {
    public static boolean isNotEmpty(String value) { return value != null && !value.isEmpty(); }
    public static void showToastShort(String value) {}
    public static String getAppVersionName() { return "test"; }
    public static String getPatchesReleaseVersion() { return "test"; }
    public static <K,V> Map<K,V> createSizeRestrictedMap(int size) { return new LinkedHashMap<>(); }
    public static <T> Future<T> submitOnBackgroundThread(Callable<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try { result.complete(task.call()); } catch (Exception ex) { result.completeExceptionally(ex); }
        return result;
    }
}
""",
    "app/revanced/extension/shared/ByteTrieSearch.java": """
package app.revanced.extension.shared;
public final class ByteTrieSearch {
    public ByteTrieSearch(byte[][] values) {}
    public static byte[][] convertStringsToBytes(String... values) { return new byte[0][]; }
}
""",
    "app/revanced/extension/shared/settings/BaseSettings.java": """
package app.revanced.extension.shared.settings;
public final class BaseSettings {
    public static final Flag DEBUG = new Flag(), DEBUG_TOAST_ON_ERROR = new Flag();
    public static final class Flag { public boolean get() { return false; } }
}
""",
    "app/revanced/extension/shared/settings/AppLanguage.java": """
package app.revanced.extension.shared.settings;
import java.util.Locale;
public enum AppLanguage {
    DEFAULT;
    public Locale getLocale() { return Locale.US; }
}
""",
    "app/revanced/extension/shared/spoof/SpoofVideoStreamsPatch.java": """
package app.revanced.extension.shared.spoof;
import app.revanced.extension.shared.settings.AppLanguage;
public final class SpoofVideoStreamsPatch {
    public static AppLanguage getLanguageOverride() { return null; }
}
""",
}


def download(relative_url):
    target = BUILD / "deps" / relative_url.rsplit("/", 1)[-1]
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(target.suffix + ".part")
        with urllib.request.urlopen(f"{MAVEN}/{relative_url}", timeout=60) as source:
            with temporary.open("wb") as output:
                shutil.copyfileobj(source, output)
        temporary.replace(target)
    return target


def main():
    system = {"Darwin": "osx", "Linux": "linux"}.get(platform.system())
    arch = {"arm64": "aarch_64", "aarch64": "aarch_64", "x86_64": "x86_64"}.get(platform.machine())
    if not system or not arch:
        raise SystemExit("This regression runner supports macOS/Linux on ARM64 or x86-64.")

    protoc = download(f"com/google/protobuf/protoc/{PROTO_VERSION}/protoc-{PROTO_VERSION}-{system}-{arch}.exe")
    protoc.chmod(0o755)
    protobuf = download(f"com/google/protobuf/protobuf-javalite/{PROTO_VERSION}/protobuf-javalite-{PROTO_VERSION}.jar")
    json = download("org/json/json/20251224/json-20251224.jar")

    # Clean generated code so removed/renamed classes cannot affect the result.
    generated, classes = BUILD / "generated", BUILD / "classes"
    for directory in (generated, classes):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)
    for relative_path, source in STUBS.items():
        destination = generated / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(source)

    proto_root = ROOT / "extensions/shared/protobuf/src/main/proto"
    subprocess.run([str(protoc), f"-I{proto_root}", f"--java_out=lite:{generated}",
                    *map(str, sorted(proto_root.rglob("*.proto")))], check=True)

    production_root = ROOT / "extensions/shared/library/src/main/java/app/revanced/extension/shared"
    sources = [production_root / path for path in (
        "spoof/ClientType.java", "spoof/requests/PlayerRoutes.java",
        "spoof/requests/StreamingDataRequest.java", "requests/Route.java", "requests/Requester.java",
    )]
    sources += sorted(generated.rglob("*.java"))
    sources += sorted((ROOT / "tests/playback/src").rglob("*.java"))
    classpath = os.pathsep.join(map(str, (protobuf, json)))
    subprocess.run(["javac", "--release", "17", "-cp", classpath, "-d", str(classes),
                    *map(str, sources)], check=True)
    subprocess.run(["java", "-ea", "-cp", str(classes) + os.pathsep + classpath,
                    "app.revanced.extension.shared.spoof.requests.PlaybackRegressionTest"], check=True)


if __name__ == "__main__":
    main()
