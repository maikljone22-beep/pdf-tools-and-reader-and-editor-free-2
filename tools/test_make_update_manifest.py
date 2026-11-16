import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from make_update_manifest import create_manifest


class ManifestTests(unittest.TestCase):
    def fixture(self, root):
        (root / "arm.apk").write_bytes(b"signed artifact fixture")
        (root / "output-metadata.json").write_text(json.dumps({"applicationId": "com.smartdocs.editor.test", "elements": [
            {"versionCode": 6, "versionName": "0.5.1-test", "outputFile": "arm.apk", "filters": [{"filterType": "ABI", "value": "arm64-v8a"}]}]}))

    def test_metadata_hash_size_and_urls_match_the_artifact(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            feed = create_manifest(root, "https://example.com/releases/test", 26)
            artifact = feed["artifacts"][0]
            self.assertEqual(6, feed["versionCode"])
            self.assertEqual(["arm64-v8a"], artifact["abis"])
            self.assertEqual("https://example.com/releases/test/arm.apk", artifact["url"])
            self.assertEqual(hashlib.sha256((root / "arm.apk").read_bytes()).hexdigest(), artifact["sha256"])
            self.assertEqual((root / "arm.apk").stat().st_size, artifact["size"])

    def test_invalid_source_and_missing_apk_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            for url in ["http://example.com", "https://user:password@example.com", "https://example.com?a=b"]:
                with self.assertRaises(ValueError): create_manifest(root, url, 26)
            (root / "arm.apk").unlink()
            with self.assertRaises(ValueError): create_manifest(root, "https://example.com", 26)

    def test_path_traversal_and_mixed_versions_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            metadata = json.loads((root / "output-metadata.json").read_text())
            metadata["elements"][0]["outputFile"] = "../outside.apk"
            (root / "output-metadata.json").write_text(json.dumps(metadata))
            with self.assertRaises(ValueError): create_manifest(root, "https://example.com", 26)
            self.fixture(root)
            metadata = json.loads((root / "output-metadata.json").read_text())
            metadata["elements"].append(dict(metadata["elements"][0], versionCode=7))
            (root / "output-metadata.json").write_text(json.dumps(metadata))
            with self.assertRaises(ValueError): create_manifest(root, "https://example.com", 26)


if __name__ == "__main__": unittest.main()
