import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("release_assets", Path(__file__).with_name("release-assets.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def fixture(self):
        assets = []
        for name in [*release.apk_names(100).values(), "SHA256SUMS.txt"]:
            path = self.root / name
            path.write_bytes(name.encode())
            assets.append(dict(name=name, state="uploaded", size=path.stat().st_size, digest="sha256:" + release.digest(path)))
        return dict(tag_name="v1", draft=True, assets=assets)

    def manifest(self, obj):
        return release.update_manifest(obj, self.root, 100, "owner/repo", "1")

    def test_requires_all_five_apks_and_checksums(self):
        obj = self.fixture()
        for asset in obj["assets"]:
            with self.subTest(missing=asset["name"]), self.assertRaises(ValueError):
                self.manifest({**obj, "assets": [a for a in obj["assets"] if a != asset]})

    def test_rejects_incomplete_corrupted_or_duplicate_assets(self):
        for field, value in [("state", "new"), ("size", 0), ("digest", "sha256:" + "00" * 32)]:
            obj = self.fixture()
            obj["assets"][1][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.manifest(obj)
        obj = self.fixture()
        obj["assets"].append(obj["assets"][0])
        with self.assertRaises(ValueError):
            self.manifest(obj)

    def test_publishes_all_architectures_and_legacy_universal_url(self):
        obj = self.fixture()
        obj["assets"].append(dict(name="update.json"))
        result = self.manifest(obj)
        self.assertFalse(result["draft"])
        self.assertTrue(obj["draft"])
        self.assertEqual(6, len(result["assets"]))
        self.assertEqual("https://github.com/owner/repo/releases/download/v1/CleanARecovery-100.apk", result["assets"][0]["browser_download_url"])

    def test_rejects_wrong_release_tag(self):
        obj = self.fixture()
        obj["tag_name"] = "v2"
        with self.assertRaises(ValueError):
            self.manifest(obj)

    def test_rejects_apk_with_wrong_architecture(self):
        path = self.root / "wrong.apk"
        with zipfile.ZipFile(path, "w") as apk:
            apk.writestr("lib/x86/libpython.so", b"test")
        with self.assertRaisesRegex(ValueError, "expected ABIs"):
            release.inspect_apk(path, ["arm64-v8a"])

    def test_rejects_missing_runtime(self):
        path = self.root / "incomplete.apk"
        with zipfile.ZipFile(path, "w") as apk:
            apk.writestr("lib/arm64-v8a/libpython.so", b"test")
        with self.assertRaisesRegex(ValueError, "missing native runtime"):
            release.inspect_apk(path, ["arm64-v8a"])


if __name__ == "__main__":
    unittest.main()
