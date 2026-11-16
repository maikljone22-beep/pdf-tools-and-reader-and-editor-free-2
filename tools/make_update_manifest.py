#!/usr/bin/env python3
"""Build a reviewable update feed from AGP APK output metadata; never uploads or publishes."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import urlparse, quote


def create_manifest(apk_dir: Path, base_url: str, min_sdk: int, notes: str = "") -> dict:
    parsed = urlparse(base_url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("base-url must be an HTTPS release asset directory without credentials, query or fragment")
    if len(notes) > 8000 or len(base_url) > 4000 or any(c.isspace() for c in base_url):
        raise ValueError("Notes or release URL exceed the update feed limits")
    if min_sdk < 1:
        raise ValueError("min-sdk must be positive")
    root = apk_dir.resolve()
    metadata = json.loads((root / "output-metadata.json").read_text())
    elements = metadata["elements"]
    if not elements or len(elements) > 16:
        raise ValueError("Expected 1 to 16 APK outputs")
    versions = {(e["versionCode"], e["versionName"]) for e in elements}
    if len(versions) != 1:
        raise ValueError("All APK variants must use the same version")
    version_code, version_name = versions.pop()
    if not isinstance(version_code, int) or isinstance(version_code, bool) or version_code <= 0 or not 1 <= len(str(version_name)) <= 100:
        raise ValueError("Invalid versionCode")
    artifacts = []
    for element in elements:
        apk = (root / element["outputFile"]).resolve()
        if apk.parent != root or apk.suffix != ".apk" or not apk.is_file():
            raise ValueError("APK outputs must exist directly inside apk-dir")
        size = apk.stat().st_size
        if not 0 < size <= 512 * 1024 * 1024:
            raise ValueError("APK size must be between 1 byte and 512 MiB")
        digest = hashlib.sha256()
        with apk.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        artifacts.append({"abis": [f["value"] for f in element.get("filters", []) if f["filterType"] == "ABI"],
                          "url": base_url.rstrip("/") + "/" + quote(apk.name, safe=""), "sha256": digest.hexdigest(), "size": size})
    return {"schemaVersion": 1, "applicationId": metadata["applicationId"], "versionCode": version_code,
            "versionName": version_name, "minSdk": min_sdk, "notes": notes, "artifacts": artifacts}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", type=Path, required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--min-sdk", type=int, default=26)
    parser.add_argument("--notes", default="")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    manifest = create_manifest(args.apk_dir, args.base_url, args.min_sdk, args.notes)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(f"Created {args.out}: {manifest['applicationId']} version {manifest['versionCode']}, {len(manifest['artifacts'])} APK variants. Nothing was published.")


if __name__ == "__main__":
    main()
