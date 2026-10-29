#!/usr/bin/env python3
"""Сборка APK Glass Notes без Gradle: aapt2 -> javac -> d8 -> упаковка -> zipalign -> apksigner.

Тот же конвейер, что у RouterApiBalance-Android (build_apk.py): приложение
использует только платформенные API, AndroidX не нужен, сборка занимает секунды.

Запуск:  python android/build_apk.py
"""
import io
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

PROJECT = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(PROJECT)
TOOLS = r"C:\Users\drts\.android-tools"
JDK = os.path.join(TOOLS, "jdk", "jdk-17.0.20.1+1")
SDK = os.path.join(TOOLS, "sdk")
BT = os.path.join(SDK, "build-tools", "35.0.0")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-35", "android.jar")

# ветка giga: Kotlin-ядро GigaAM + onnxruntime
TOOLS_ROOT = TOOLS   # .android-tools и есть корень: jdk, sdk, kotlin, libs
KOTLIN_SRC = os.path.join(PROJECT, "kotlin")
KOTLIN_LIBS = os.path.join(TOOLS_ROOT, "libs", "onnxruntime")
KOTLINC_LIB = os.path.join(TOOLS_ROOT, "kotlin", "kotlinc", "lib", "*")
NATIVE_LIBS = os.path.join(KOTLIN_LIBS, "arm64-v8a")
DEX_JARS = ["onnxruntime.jar", "kotlin-stdlib.jar", "kotlinx-coroutines-core-jvm.jar"]

BUILD = os.path.join(PROJECT, "build")
CLASSES = os.path.join(BUILD, "classes")
GEN = os.path.join(BUILD, "gen")
DEX = os.path.join(BUILD, "dex")
RES_ZIP = os.path.join(BUILD, "res.zip")
BASE_APK = os.path.join(BUILD, "base.apk")
UNSIGNED = os.path.join(BUILD, "unsigned.apk")
ALIGNED = os.path.join(BUILD, "aligned.apk")
FINAL = os.path.join(PROJECT, "glass-notes.apk")
KEYSTORE = os.path.join(PROJECT, "debug.keystore")
PACKAGE_PATH = os.path.join("com", "glassnotes", "app")

# что уезжает в assets APK (исходники приложения живут в корне проекта)
ASSET_FILES = ["index.html", "sw.js", "manifest.json", "icon-192.png",
               "icon-512.png", "icon-512-maskable.png", "apple-touch-icon.png"]


def env():
    e = dict(os.environ)
    e["JAVA_HOME"] = JDK
    e["PATH"] = os.path.join(JDK, "bin") + os.pathsep + e.get("PATH", "")
    return e


def run(args, what):
    print(">> %s" % what)
    r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", env=env(), cwd=PROJECT)
    if r.returncode != 0:
        print("!! %s: код %d" % (what, r.returncode))
        print((r.stdout or "")[-3000:])
        print((r.stderr or "")[-3000:])
        sys.exit(1)
    tail = ((r.stdout or "") + (r.stderr or "")).strip()
    if tail:
        print("   " + "\n   ".join(tail.splitlines()[-4:]))


def bat(path):
    return ["cmd", "/c", path]


def manifest_version():
    """Версия берётся из манифеста — единственный источник правды."""
    text = io.open(os.path.join(PROJECT, "AndroidManifest.xml"), encoding="utf-8").read()
    m = re.search(r'android:versionName="([^"]+)"', text)
    return m.group(1) if m else "0.0"


def sync_assets():
    assets = os.path.join(PROJECT, "assets")
    os.makedirs(assets, exist_ok=True)
    for name in ASSET_FILES:
        src = os.path.join(ROOT, name)
        if not os.path.exists(src):
            print("нет файла приложения: %s" % src)
            sys.exit(1)
        dst = os.path.join(assets, name)
        if name == "sw.js":
            # имя кэша зависит от версии: иначе обновлённый APK живёт со старым кэшем
            text = io.open(src, encoding="utf-8").read()
            text = re.sub(r"const VERSION = '[^']*';",
                          "const VERSION = 'glass-notes-%s';" % manifest_version(), text, count=1)
            io.open(dst, "w", encoding="utf-8", newline="").write(text)
        elif name == "index.html":
            # версию и дату сборки вписывает сама сборка: руками они разъезжаются
            text = io.open(src, encoding="utf-8").read()
            text = re.sub(r"const APP_VERSION = '[^']*';",
                          "const APP_VERSION = '%s';" % manifest_version(), text, count=1)
            text = re.sub(r"const BUILD_DATE = '[^']*';",
                          "const BUILD_DATE = '%s';" % time.strftime("%d.%m.%Y"), text, count=1)
            io.open(dst, "w", encoding="utf-8", newline="").write(text)
            print(">> assets: index.html -> версия %s, сборка %s" %
                  (manifest_version(), time.strftime("%d.%m.%Y")))
        else:
            shutil.copy2(src, dst)
    # тексты лицензий сторонних компонентов — внутрь APK (требование MIT/Apache при распространении)
    lic_dir = os.path.join(PROJECT, "licenses")
    lic_dst = os.path.join(assets, "licenses")
    lic_count = 0
    if os.path.isdir(lic_dir):
        if os.path.isdir(lic_dst):
            shutil.rmtree(lic_dst)
        os.makedirs(lic_dst)
        for lic in sorted(os.listdir(lic_dir)):
            if lic.endswith(".txt"):
                shutil.copy2(os.path.join(lic_dir, lic), os.path.join(lic_dst, lic))
                lic_count += 1
    print(">> assets: %d файлов приложения + native-shim.js + %d лицензий" %
          (len(ASSET_FILES), lic_count))


def main():
    for tool, path in (("aapt2", os.path.join(BT, "aapt2.exe")),
                       ("d8", os.path.join(BT, "d8.bat")),
                       ("zipalign", os.path.join(BT, "zipalign.exe")),
                       ("apksigner", os.path.join(BT, "apksigner.bat")),
                       ("android.jar", ANDROID_JAR),
                       ("keytool", os.path.join(JDK, "bin", "keytool.exe"))):
        if not os.path.exists(path):
            print("нет %s: %s" % (tool, path))
            return 1

    sync_assets()
    shutil.rmtree(BUILD, ignore_errors=True)
    for d in (CLASSES, GEN, DEX):
        os.makedirs(d)

    run([os.path.join(BT, "aapt2.exe"), "compile", "--dir", "res", "-o", RES_ZIP],
        "aapt2 compile (ресурсы)")

    run([os.path.join(BT, "aapt2.exe"), "link", "-o", BASE_APK,
         "-I", ANDROID_JAR,
         "--manifest", "AndroidManifest.xml",
         "-R", RES_ZIP,
         "-A", "assets",
         "--java", GEN,
         "--auto-add-overlay"], "aapt2 link (манифест + ресурсы + assets)")

    kt_sources = []
    for root, _dirs, files in os.walk(KOTLIN_SRC):
        kt_sources += [os.path.join(root, f) for f in files if f.endswith(".kt")]
    kt_sources.sort()
    if kt_sources:
        # компилятор Kotlin зовём напрямую через java: kotlinc.bat режет classpath по ';'
        kotlin_cp = ";".join([ANDROID_JAR] + [os.path.join(KOTLIN_LIBS, j) for j in DEX_JARS])
        run([os.path.join(JDK, "bin", "java.exe"), "-cp", KOTLINC_LIB,
             "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
             "-classpath", kotlin_cp, "-jvm-target", "1.8", "-nowarn",
             "-d", CLASSES] + kt_sources, "kotlinc (ядро GigaAM, %d файлов)" % len(kt_sources))

    java_sources = []
    for root, _dirs, files in os.walk(os.path.join(PROJECT, "java")):
        java_sources += [os.path.join(root, f) for f in files if f.endswith(".java")]
    java_sources.sort()
    java_sources.append(os.path.join(GEN, PACKAGE_PATH, "R.java"))

    run([os.path.join(JDK, "bin", "javac.exe"),
         "-source", "8", "-target", "8",
         "-classpath", ";".join([ANDROID_JAR, CLASSES] + [os.path.join(KOTLIN_LIBS, j) for j in DEX_JARS]),
         "-encoding", "UTF-8", "-nowarn",
         "-d", CLASSES] + java_sources, "javac (компиляция Java)")

    class_files = []
    for root, _dirs, files in os.walk(CLASSES):
        class_files += [os.path.join(root, f) for f in files if f.endswith(".class")]

    # javac и kotlinc пишут в один каталог: без дедупликации d8 ругается
    # «Type … is defined multiple times»
    class_files = sorted(set(class_files))
    dex_inputs = class_files + [os.path.join(KOTLIN_LIBS, j) for j in DEX_JARS]
    run(bat(os.path.join(BT, "d8.bat")) + ["--release", "--lib", ANDROID_JAR,
                                           "--min-api", "24",
                                           "--output", DEX] + dex_inputs, "d8 (dex)")

    dex_file = os.path.join(DEX, "classes.dex")
    if not os.path.exists(dex_file):
        print("d8 не создал classes.dex")
        return 1

    with zipfile.ZipFile(BASE_APK) as src, zipfile.ZipFile(UNSIGNED, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            dst.writestr(item, src.read(item.filename))
        dst.write(dex_file, "classes.dex", compress_type=zipfile.ZIP_STORED)
        native_count = 0
        if os.path.isdir(NATIVE_LIBS):
            for so in sorted(os.listdir(NATIVE_LIBS)):
                if so.endswith(".so"):
                    dst.write(os.path.join(NATIVE_LIBS, so), "lib/arm64-v8a/" + so, compress_type=zipfile.ZIP_DEFLATED)
                    native_count += 1
        print(">> нативных библиотек в APK: %d" % native_count)
    print(">> упаковка APK: %d КБ (dex %d КБ)" % (
        os.path.getsize(UNSIGNED) // 1024, os.path.getsize(dex_file) // 1024))

    run([os.path.join(BT, "zipalign.exe"), "-f", "4", UNSIGNED, ALIGNED], "zipalign")

    if not os.path.exists(KEYSTORE):
        run([os.path.join(JDK, "bin", "keytool.exe"), "-genkeypair",
             "-keystore", KEYSTORE, "-alias", "androiddebugkey",
             "-storepass", "android", "-keypass", "android",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=Android Debug,O=Android,C=US"], "keytool (debug-ключ)")

    run(bat(os.path.join(BT, "apksigner.bat")) + ["sign",
         "--ks", KEYSTORE, "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--ks-key-alias", "androiddebugkey",
         "--out", FINAL, ALIGNED], "apksigner (подпись)")

    print()
    run(bat(os.path.join(BT, "apksigner.bat")) + ["verify", "--verbose", FINAL], "проверка подписи")
    run([os.path.join(BT, "aapt2.exe"), "dump", "badging", FINAL], "сведения о пакете")
    run([os.path.join(BT, "aapt2.exe"), "dump", "permissions", FINAL], "разрешения в манифесте")

    with zipfile.ZipFile(FINAL) as z:
        names = z.namelist()
    print(">> содержимое APK: %d записей" % len(names))
    for n in sorted(names):
        print("   " + n)

    dist = os.path.join(ROOT, "dist")
    os.makedirs(dist, exist_ok=True)
    manifest = io.open(os.path.join(PROJECT, 'AndroidManifest.xml'), encoding='utf-8').read()
    vm = re.search(r'android:versionName="([^"]+)"', manifest)
    version = vm.group(1) if vm else 'dev'
    shutil.copy2(FINAL, os.path.join(dist, 'glass-notes-%s.apk' % version))
    print('копия: dist/glass-notes-%s.apk' % version)

    print()
    print("ГОТОВО: %s (%d КБ)" % (FINAL, os.path.getsize(FINAL) // 1024))
    return 0


if __name__ == "__main__":
    sys.exit(main())
