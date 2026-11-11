import time
import unittest

from app.analysis import compute_stats, hidden_origin
from app.db import Store, make_engine, sessions
from app.merge import Network, merge_observations

def net(bssid="70:AA:BB:00:00:01", **kw):
    base = dict(ssid="", ssid_hidden=True, channel=6, rssi=-70, encryption="WPA2", times_seen=1,
                last_session_id="s", last_seen_at_ms=0)
    base.update(kw)
    if base["ssid"]:
        base["ssid_hidden"] = False
    return Network(bssid=bssid, **base)

class MergeTest(unittest.TestCase):
    def test_name_never_replaced_by_hidden(self):
        m = merge_observations(net(ssid="Casa"), net())
        self.assertEqual((m.ssid, m.ssid_hidden), ("Casa", False))

    def test_name_fills_hidden(self):
        m = merge_observations(net(), net(ssid="Casa"))
        self.assertEqual(m.ssid, "Casa")

    def test_rename_only_when_newer_date_known(self):
        old = net(ssid="A", last_seen_at_ms=100)
        self.assertEqual(merge_observations(old, net(ssid="B", last_seen_at_ms=200)).ssid, "B")
        self.assertEqual(merge_observations(old, net(ssid="B", last_seen_at_ms=0)).ssid, "A")

    def test_strongest_capture_position_and_rssi_travel_together(self):
        weak = net(rssi=-90, lat=1.0, lon=2.0)
        strong = net(rssi=-40, lat=3.0, lon=4.0)
        m = merge_observations(weak, strong)
        self.assertEqual((m.rssi, m.lat, m.lon), (-40, 3.0, 4.0))
        m = merge_observations(strong, weak)
        self.assertEqual((m.rssi, m.lat, m.lon), (-40, 3.0, 4.0))

    def test_position_never_erased_by_capture_without_fix(self):
        m = merge_observations(net(rssi=-90, lat=1.0, lon=2.0), net(rssi=-30))
        self.assertEqual((m.lat, m.lon, m.rssi), (1.0, 2.0, -90))

    def test_idempotent(self):
        a = net(ssid="Casa", rssi=-50, lat=1.0, lon=2.0, times_seen=5, last_seen_at_ms=9)
        self.assertEqual(merge_observations(a, a), a)

    def test_times_seen_is_max_not_sum(self):
        self.assertEqual(merge_observations(net(times_seen=5), net(times_seen=3)).times_seen, 5)

class StoreTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(make_engine("sqlite://"))
        self.store.create_schema()

    def test_import_counts_and_idempotence(self):
        recs = [net("70:AA:BB:00:00:01", ssid="A", lat=1.0, lon=2.0), net("70:AA:BB:00:00:02")]
        first = self.store.apply_import(recs, "web", "generico", 2, 0)
        self.assertEqual((first["added"], first["updated"], first["total"], first["with_position"]), (2, 0, 2, 1))
        again = self.store.apply_import(recs, "web", "generico", 2, 0)
        self.assertEqual((again["added"], again["updated"], again["total"]), (0, 0, 2))

    def test_name_filled_counted(self):
        self.store.apply_import([net("70:AA:BB:00:00:02")], "web", "g", 1, 0)
        r = self.store.apply_import([net("70:AA:BB:00:00:02", ssid="Agora tem nome")], "web", "g", 1, 0)
        self.assertEqual((r["updated"], r["names_filled"]), (1, 1))
        self.assertEqual(self.store.all_networks()[0].ssid, "Agora tem nome")

    def test_more_than_one_chunk(self):
        recs = [net("70:AA:BB:00:%02X:%02X" % (i // 256, i % 256)) for i in range(1200)]
        self.assertEqual(self.store.apply_import(recs, "web", "g", 1200, 0)["total"], 1200)
        self.assertEqual(self.store.apply_import(recs, "web", "g", 1200, 0)["updated"], 0)

    def test_version_changes_with_data(self):
        v0 = self.store.version()
        self.store.apply_import([net()], "web", "g", 1, 0)
        self.assertNotEqual(v0, self.store.version())

    def test_term_sessions_store_only_hash_expire_and_follow_version(self):
        self.store.create_term_session("tok-abc", "v1", 60)
        self.assertTrue(self.store.term_valid("tok-abc", "v1"))
        self.assertFalse(self.store.term_valid("tok-abc", "v2"))
        self.assertFalse(self.store.term_valid("outro", "v1"))
        with self.store.engine.connect() as c:
            hashes = [r[0] for r in c.execute(sessions.select())]
        self.assertNotIn("tok-abc", hashes)
        self.store.create_term_session("velho", "v1", -1)
        self.assertFalse(self.store.term_valid("velho", "v1"))
        self.store.delete_session("tok-abc")
        self.assertFalse(self.store.term_valid("tok-abc", "v1"))

    def test_old_database_gets_new_columns_and_backfill(self):
        from sqlalchemy import text
        eng = make_engine("sqlite://")
        with eng.begin() as c:
            c.execute(text("CREATE TABLE networks (bssid VARCHAR(17) PRIMARY KEY, ssid VARCHAR(64) NOT NULL, ssid_hidden BOOLEAN NOT NULL,"
                           " channel INTEGER NOT NULL, rssi INTEGER NOT NULL, encryption VARCHAR(16) NOT NULL, times_seen BIGINT NOT NULL,"
                           " wps_active BOOLEAN NOT NULL, lat DOUBLE, lon DOUBLE, last_session_id VARCHAR(64) NOT NULL,"
                           " last_seen_at_ms BIGINT NOT NULL, updated_at_ms BIGINT NOT NULL)"))
            c.execute(text("INSERT INTO networks VALUES ('72:AA:BB:00:00:01','VIVO-1234',0,6,-50,'WPA2',1,0,-21.1378,-48.9728,'s',1,1)"))
        store = Store(eng)
        store.create_schema()
        with eng.connect() as c:
            row = c.execute(text("SELECT city, local_admin, default_name FROM networks")).one()
        self.assertEqual(tuple(row), ("Catanduva - SP", 1, 1))

class AnalysisTest(unittest.TestCase):
    def test_hidden_origin_sibling_and_local_admin(self):
        visible = net("70:AA:BB:11:22:33", ssid="Casa")
        sibling = net("72:AA:BB:11:22:44")
        stranger = net("00:11:22:33:44:55")
        r = hidden_origin([visible, sibling, stranger])
        self.assertEqual(r, {"unnamed": 2, "with_visible_sibling": 1, "locally_administered": 1})

    def test_stats(self):
        nets = [net("70:AA:BB:00:00:01", ssid="VIVO-1234", encryption="OPEN", channel=1, lat=1.0, lon=2.0),
                net("70:AA:BB:00:00:02", ssid="Casa", encryption="WPA2", channel=6, wps_active=True),
                net("70:AA:BB:00:00:03", encryption="WEP", channel=6),
                net("70:AA:BB:00:00:04", ssid="Casa", encryption="WPA3", channel=40)]
        s = compute_stats(nets)
        self.assertEqual((s["total"], s["with_position"], s["hidden"], s["wps"], s["weak"], s["default_names"]),
                         (4, 1, 1, 1, 2, 1))
        self.assertEqual(s["top_ssids"][0], {"ssid": "Casa", "count": 2})
        chans = {c["channel"]: c["count"] for c in s["channels"]}
        self.assertEqual((chans[1], chans[6], chans[11], s["channels_other"]), (1, 2, 0, 1))
        self.assertEqual([e["name"] for e in s["encryption"]], ["OPEN", "WEP", "WPA2", "WPA3"])

    def test_empty(self):
        s = compute_stats([])
        self.assertEqual((s["total"], s["hidden"], s["first_seen_ms"]), (0, 0, None))

if __name__ == "__main__":
    unittest.main()
