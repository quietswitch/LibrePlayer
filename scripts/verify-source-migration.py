"""Host SQLite authority using SQL recorded by SourceMigrationTest. Run unit tests first."""
import hashlib
import json
import sqlite3
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
schemas = repo / "app/schemas/com.libreplayer.data.database.AppDatabase"
v1 = json.loads((schemas / "1.json").read_text())["database"]
v2 = json.loads((schemas / "2.json").read_text())["database"]
old = {e["tableName"]: e for e in v1["entities"]}
new = {e["tableName"]: e for e in v2["entities"]}
assert v1["version"] == 1 and v2["version"] == 2
assert all(new[name] == definition for name, definition in old.items()), "Existing schema changed"
assert set(new) - set(old) == {"library_sources", "song_sources", "legacy_song_protection"}
evidence = repo / "performance-results/q3.7-storage"
evidence.mkdir(parents=True, exist_ok=True)
db_path = evidence / "synthetic-host-v1-v2.db"
# Only this explicitly named synthetic artifact is recreated.
db_path.unlink(missing_ok=True)
db = sqlite3.connect(db_path)
db.execute("PRAGMA foreign_keys=ON")
for entity in old.values():
    db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for index in entity.get("indices", []):
        db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
for sql in v1["setupQueries"]:
    db.execute(sql)
db.execute("PRAGMA user_version=1")
db.executescript((repo / "app/build/migration-pure/fixture.sql").read_text())


def snapshot():
    return {name: db.execute(f'SELECT * FROM "{name}" ORDER BY 1,2').fetchall() for name in old}


before = snapshot()
db.executescript("BEGIN;\n" + (repo / "app/build/migration-pure/migration.sql").read_text() + "\nPRAGMA user_version=2; COMMIT;")
after = snapshot()
assert before == after, "Pre-existing rows or references changed"
assert db.execute("PRAGMA foreign_key_check").fetchall() == []
assert db.execute("SELECT songId FROM legacy_song_protection").fetchall() == [("media:43",)]
assert db.execute("SELECT count(*) FROM song_sources").fetchone()[0] == 4
assert db.execute("SELECT count(*) FROM library_sources WHERE version IS NOT NULL OR generation IS NOT NULL").fetchone()[0] == 0
for name in set(new) - set(old):
    assert {row[1] for row in db.execute(f'PRAGMA table_info("{name}")')} == {f["columnName"] for f in new[name]["fields"]}
for label, value in [("before", before), ("after", after)]:
    (evidence / f"host-user-rows-{label}.json").write_text(json.dumps(value, indent=2), encoding="utf-8")
payload = json.dumps(before, sort_keys=True).encode()
print(json.dumps({"result": "PASS", "v1_schema_reused": True, "existing_tables_unchanged": list(old),
                  "added_tables": sorted(set(new)-set(old)), "existing_song_ids_changed": False,
                  "user_rows_sha256": hashlib.sha256(payload).hexdigest()}, indent=2))
db.close()
