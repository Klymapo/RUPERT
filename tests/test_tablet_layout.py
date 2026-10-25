from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android" / "app" / "src" / "main"


class TabletLayoutTests(unittest.TestCase):
    def test_tablet_files_exist(self):
        required = [
            "android/settings.gradle.kts",
            "android/app/build.gradle.kts",
            "android/app/src/main/AndroidManifest.xml",
            "android/app/src/main/java/io/klymapo/rupert/MainActivity.kt",
            "android/app/src/main/java/io/klymapo/rupert/RupertService.kt",
            "android/app/src/main/java/io/klymapo/rupert/WakeWord.kt",
            "android/app/src/main/java/io/klymapo/rupert/RupertCloud.kt",
            ".github/workflows/android.yml",
            ".github/workflows/supabase-heartbeat.yml",
            ".github/workflows/supabase-deploy.yml",
            "docs/TABLET.md",
        ]
        for relative in required:
            self.assertTrue((ROOT / relative).exists(), relative)

    def test_every_face_state_has_art(self):
        for state in ("sleep", "listen", "think", "talk", "excited", "serious", "full"):
            self.assertTrue((APP / "res" / "drawable-nodpi" / f"rupert_{state}.webp").exists(), state)

    def test_microphone_service_is_declared(self):
        manifest = (APP / "AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertIn('android:foregroundServiceType="microphone"', manifest)
        self.assertIn("FOREGROUND_SERVICE_MICROPHONE", manifest)

    def test_tablet_never_holds_server_secrets(self):
        source = "\n".join(p.read_text(encoding="utf-8") for p in APP.rglob("*.kt"))
        for secret in ("GEMINI_API_KEY", "CF_API_TOKEN", "CF_ACCOUNT_ID", "service_role", "SERVICE_ROLE"):
            self.assertNotIn(secret, source)

    def test_brain_uses_gemini_with_cloudflare_fallback(self):
        source = (ROOT / "supabase/functions/rupert/index.ts").read_text(encoding="utf-8")
        self.assertIn("GEMINI_API_KEY", source)
        self.assertIn("geminiPrompt", source)
        self.assertIn("cloudflarePrompt", source)


if __name__ == "__main__":
    unittest.main()
