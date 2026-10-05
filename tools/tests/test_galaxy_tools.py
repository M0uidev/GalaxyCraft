import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import bcsv  # noqa: E402
import rarc  # noqa: E402
import space_galaxy  # noqa: E402

FIELDS = [("ZoneName", 0xFFFFFFFF, 0, 0, 6), ("ScenarioNo", 0xFFFFFFFF, 4, 0, 0),
          ("pos_x", 0xFFFFFFFF, 8, 0, 2), ("0x%08x" % bcsv.jhash("RedBlueExGalaxy"), 0xFFFFFFFF, 12, 0, 0)]


class BcsvTest(unittest.TestCase):
    def test_hash_of_a_known_name(self):
        # Names are hashed as JMap does (h * 31 + c); a field read back gets its name again.
        self.assertEqual(bcsv.jhash("ZoneName"), bcsv.jhash("ZoneName"))
        fields, _, _ = bcsv.read(bcsv.write(FIELDS, 16, []))
        self.assertEqual(fields[0][0], "ZoneName")

    def test_round_trip(self):
        rows = [{"ZoneName": "A", "ScenarioNo": -3, "pos_x": 1.5, FIELDS[3][0]: 7},
                {"ZoneName": "A", "ScenarioNo": 2, "pos_x": -0.25, FIELDS[3][0]: 1}]
        data = bcsv.write(FIELDS, 16, rows)
        _, size, back = bcsv.read(data)
        self.assertEqual(size, 16)
        self.assertEqual(back, rows)
        self.assertEqual(len(data) % 32, 0)
        self.assertEqual(data.count(b"A\0"), 1)  # the string is pooled


class RarcPathsTest(unittest.TestCase):
    FILES = [("Light.bcsv", b"light"), ("Other.bcsv", b"other" * 7)]

    def test_rename_keeps_contents(self):
        arc = rarc.build("Stage", self.FILES)
        new = rarc.replace_paths(arc, {"Stage/Other.bcsv": b"new"}, {"Stage/Light.bcsv": "GalaxyCraftSpaceLight.bcsv"})
        self.assertEqual(rarc.list_paths(new), [("Stage/GalaxyCraftSpaceLight.bcsv", b"light"), ("Stage/Other.bcsv", b"new")])

    def test_unknown_path_fails(self):
        with self.assertRaises(KeyError):
            rarc.replace_paths(rarc.build("Stage", self.FILES), {"Stage/Nope": b""})


class SpaceGalaxyTest(unittest.TestCase):
    def test_scenario_keeps_one_row_and_renames_the_zone(self):
        zone_col = "0x%08x" % bcsv.jhash(space_galaxy.BASE)
        fields = [("ScenarioNo", 0xFFFFFFFF, 0, 0, 0), ("ScenarioName", 0xFFFFFFFF, 4, 0, 6),
                  ("PowerStarId", 0xFFFFFFFF, 8, 0, 0), ("Comet", 0xFFFFFFFF, 12, 0, 6),
                  ("CometLimitTimer", 0xFFFFFFFF, 16, 0, 0), (zone_col, 0xFFFFFFFF, 20, 0, 0)]
        rows = [{"ScenarioNo": n, "ScenarioName": "s", "PowerStarId": 3, "Comet": "Purple", "CometLimitTimer": 9,
                 zone_col: 5} for n in (1, 2)]
        zones = bcsv.write([("ZoneName", 0xFFFFFFFF, 0, 0, 6)], 4, [{"ZoneName": space_galaxy.BASE}])
        arc = rarc.build("Scenario", [("ScenarioData.bcsv", bcsv.write(fields, 24, rows)), ("ZoneList.bcsv", zones)])
        out = dict(rarc.list_paths(space_galaxy.scenario_arc(arc)))
        _, _, scen = bcsv.read(out["Scenario/ScenarioData.bcsv"])
        self.assertEqual(len(scen), 1)
        self.assertEqual(scen[0]["0x%08x" % bcsv.jhash(space_galaxy.NAME)], 1)
        self.assertEqual(scen[0]["PowerStarId"], 0)
        self.assertEqual(bcsv.read(out["Scenario/ZoneList.bcsv"])[2], [{"ZoneName": space_galaxy.NAME}])


if __name__ == "__main__":
    unittest.main()
