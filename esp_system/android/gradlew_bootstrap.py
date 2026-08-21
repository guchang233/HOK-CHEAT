#!/usr/bin/env python3
"""
Gradle wrapper bootstrap - downloads gradle if needed and runs build.
Replaces gradlew + gradle-wrapper.jar dependency.
"""
import os
import sys
import subprocess
import urllib.request
import zipfile
import shutil
import platform
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR

GRADLE_VERSION = "8.2"
GRADLE_URL = f"https://services.gradle.org/distributions/gradle-{GRADLE_VERSION}-bin.zip"
GRADLE_HOME = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
GRADLE_DIST = GRADLE_HOME / "wrapper" / "dists" / f"gradle-{GRADLE_VERSION}"
GRADLE_BIN = GRADLE_DIST / f"gradle-{GRADLE_VERSION}" / "bin" / "gradle"

def download_gradle():
    if GRADLE_BIN.exists():
        return GRADLE_BIN
    
    GRADLE_DIST.mkdir(parents=True, exist_ok=True)
    zip_path = GRADLE_DIST / f"gradle-{GRADLE_VERSION}.zip"
    
    print(f"[*] Downloading Gradle {GRADLE_VERSION}...")
    try:
        urllib.request.urlretrieve(GRADLE_URL, zip_path)
    except Exception as e:
        print(f"[!] Failed to download gradle: {e}")
        sys.exit(1)
    
    print(f"[*] Extracting Gradle...")
    with zipfile.ZipFile(zip_path, 'r') as zf:
        zf.extractall(GRADLE_DIST)
    zip_path.unlink()
    
    if GRADLE_BIN.exists():
        os.chmod(GRADLE_BIN, 0o755)
        return GRADLE_BIN
    print(f"[!] gradle binary not found after extraction")
    sys.exit(1)

def main():
    gradle = download_gradle()
    
    args = sys.argv[1:]
    if not args:
        args = ["assembleDebug"]
    
    cmd = [str(gradle)] + args + ["--no-daemon"]
    print(f"[*] Running: {' '.join(cmd)}")
    
    env = os.environ.copy()
    sdk = os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", ""))
    if sdk:
        env["ANDROID_HOME"] = sdk
        env["ANDROID_SDK_ROOT"] = sdk
    
    result = subprocess.run(cmd, cwd=str(PROJECT_DIR), env=env)
    sys.exit(result.returncode)

if __name__ == "__main__":
    main()
