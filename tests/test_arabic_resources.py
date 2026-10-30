"""Guard the launcher's Arabic: the mirrored Home keeps every view, and nothing is left in English.

Arabic Home is its own layout (layout-ar), mirrored like Hebrew's, so a view added to the default layout and not to
it would be missing in Arabic, or crash Home there as a missing Hebrew view once did. A string missing from
values-ar would show in English on an Arabic TV.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


RES = Path(__file__).resolve().parents[1] / "launcher/app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def views(folder, layout):
    return {v.attrib[ANDROID + "id"]: v.tag
            for v in ET.parse(RES / folder / layout).iter()
            if ANDROID + "id" in v.attrib}


def translatable(folder):
    return {e.get("name") for e in ET.parse(RES / folder / "strings.xml").getroot()
            if e.tag in ("string", "plurals", "string-array") and e.get("translatable") != "false"}


class ArabicResourcesTest(unittest.TestCase):
    def test_arabic_layouts_keep_every_default_view(self):
        for layout in ("activity_main.xml", "dream_clock.xml", "item_input.xml"):
            default, arabic = views("layout", layout), views("layout-ar", layout)
            for view_id, tag in default.items():
                with self.subTest(layout=layout, view=view_id):
                    self.assertEqual(tag, arabic.get(view_id))

    def test_every_string_has_an_arabic_translation(self):
        missing = sorted(translatable("values") - translatable("values-ar"))
        self.assertEqual(missing, [], "translate these in values-ar/strings.xml")

    def test_arabic_home_is_mirrored(self):
        flags = {b.get("name"): b.text for b in ET.parse(RES / "values-ar" / "bools.xml").getroot()}
        self.assertEqual(flags.get("rtl_menu_bar"), "true")
        self.assertEqual(flags.get("rtl_app_grid"), "true")

    def test_arabic_fonts_are_in_the_app(self):
        for f in ET.parse(RES / "values-ar" / "fonts.xml").getroot():
            with self.subTest(font=f.get("name")):
                self.assertTrue(f.text.startswith("@font/"), f.text)
                self.assertTrue((RES / "font" / (f.text[len("@font/"):] + ".ttf")).is_file(), f.text)

    def test_arabic_pills_have_their_icon_on_the_right(self):
        # The app leaves RTL support off, so drawableStart is the left even in Arabic: the mirrored pills say End.
        pills = {v.attrib[ANDROID + "id"]: v.attrib for v in ET.parse(RES / "layout-ar" / "activity_main.xml").iter()
                 if ANDROID + "id" in v.attrib}
        for pill in ("@+id/btnSettings", "@+id/btnLanguage", "@+id/btnWifi", "@+id/btnInputs"):
            with self.subTest(pill=pill):
                self.assertEqual(pills[pill].get("style"), "@style/TopButtonMirrored")
                self.assertIn(ANDROID + "drawableEnd", pills[pill])
                self.assertNotIn(ANDROID + "drawableStart", pills[pill])

    def test_hijri_months_are_all_there(self):
        for folder in ("values", "values-ar"):
            with self.subTest(folder=folder):
                months = [a for a in ET.parse(RES / folder / "strings.xml").getroot() if a.get("name") == "hijri_months"]
                self.assertEqual([len(list(m)) for m in months], [12])


if __name__ == "__main__":
    unittest.main()
