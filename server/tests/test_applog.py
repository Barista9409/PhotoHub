"""Phones report stops, errors and timings; the Synology groups them."""
from fastapi.testclient import TestClient

from photohub.api import create_app
from photohub.users import add_user

TRACE = """java.lang.IllegalStateException: Key "h:d2026-10-31" was already used
\tat androidx.compose.foundation.lazy.layout.Foo.bar(Foo.kt:1)
\tat com.photohub.app.ui.PhotoGridKt.MediaGrid(PhotoGrid.kt:245)"""


def test_logs_are_grouped(settings, db):
    _, token = add_user(db, settings, "reo")
    c = TestClient(create_app(settings, db))
    h = {"Authorization": f"Bearer {token}"}
    items = [{"kind": "crash", "name": "stop", "msg": TRACE}, {"kind": "crash", "name": "stop", "msg": TRACE},
             {"kind": "error", "name": "sync: HTTP 500"}] + [{"kind": "time", "name": "timeline", "ms": ms} for ms in range(100, 2100, 100)]
    assert c.post("/api/logs", json={"device": "Pixel", "version": "0.34.0", "items": items}, headers=h).json()["stored"] == 23
    s = c.get("/api/logs/summary", headers=h).json()
    assert s["stops"][0]["count"] == 2 and "PhotoGrid.kt:245" in s["stops"][0]["what"]
    assert s["errors"][0]["what"] == "sync: HTTP 500"
    t = s["timing"][0]
    assert t["name"] == "timeline" and t["count"] == 20 and t["max"] == 2000 and t["median"] == 1100
    assert s["devices"][0]["stops"] == 2 and s["devices"][0]["version"] == "0.34.0"
