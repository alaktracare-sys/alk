from fastapi import APIRouter, Form, Request, HTTPException, Query
from database import get_db, attach_artists_to_song_dicts
import os
import random
import time
from typing import List, Optional

router = APIRouter(prefix="/club", tags=["club"])

CLUB_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def get_base_url(request: Request) -> str:
    env_host = os.getenv("HOST_URL")
    if env_host:
        return env_host.rstrip("/")
    if request:
        host = request.headers.get("host")
        proto = request.headers.get("x-forwarded-proto", request.url.scheme)
        if host:
            return f"{proto}://{host}"
    return "https://api.alaktracare.in"


def generate_club_code(db) -> str:
    for _ in range(50):
        code = "".join(random.choices(CLUB_CODE_ALPHABET, k=6))
        row = db.execute("SELECT id FROM clubs WHERE code = ?", (code,)).fetchone()
        if not row:
            return code
    return "".join(random.choices(CLUB_CODE_ALPHABET, k=8))


def delete_club_cascade(db, club_id: int):
    db.execute("DELETE FROM club_songs WHERE club_id = ?", (club_id,))
    db.execute("DELETE FROM club_members WHERE club_id = ?", (club_id,))
    db.execute("DELETE FROM clubs WHERE id = ?", (club_id,))
    db.commit()


def insert_member(db, club_id: int, user_id: int, username: str, now_ms: int):
    """Safely inserts member whether table has device_id column or not."""
    try:
        col_rows = db.execute("PRAGMA table_info(club_members)").fetchall()
        cols = [col[1] for col in col_rows]
        if "device_id" in cols:
            db.execute(
                """
                INSERT OR REPLACE INTO club_members (club_id, user_id, device_id, username, last_active_ms)
                VALUES (?, ?, 'default', ?, ?)
                """,
                (club_id, user_id, username.strip(), now_ms)
            )
        else:
            db.execute(
                """
                INSERT OR REPLACE INTO club_members (club_id, user_id, username, last_active_ms)
                VALUES (?, ?, ?, ?)
                """,
                (club_id, user_id, username.strip(), now_ms)
            )
    except Exception:
        try:
            db.execute(
                "INSERT OR IGNORE INTO club_members (club_id, user_id, username, last_active_ms) VALUES (?, ?, ?, ?)",
                (club_id, user_id, username.strip(), now_ms)
            )
        except Exception:
            pass


@router.post("/create")
def create_club(
    request: Request,
    user_id: int = Form(...),
    username: str = Form(...),
    name: str = Form("Collaborative Playlist"),
    description: str = Form("")
):
    """Creates a permanent collaborative club playlist."""
    db = get_db()
    try:
        trimmed_name = name.strip() or "Collaborative Playlist"
        code = generate_club_code(db)
        now_ms = int(time.time() * 1000)

        cursor = db.execute(
            """
            INSERT INTO clubs (code, name, owner_user_id, owner_username, updated_at_ms)
            VALUES (?, ?, ?, ?, ?)
            """,
            (code, trimmed_name, user_id, username.strip(), now_ms)
        )
        club_id = cursor.lastrowid

        # Add creator as owner member
        insert_member(db, club_id, user_id, username, now_ms)
        db.commit()

        return {
            "success": True,
            "club_id": club_id,
            "code": code,
            "name": trimmed_name,
            "owner_user_id": user_id,
            "owner_username": username.strip()
        }
    finally:
        db.close()


@router.post("/join")
def join_club(
    request: Request,
    user_id: int = Form(...),
    username: str = Form(...),
    code: str = Form(...)
):
    """Joins a collaborative club playlist using a 6-character code."""
    db = get_db()
    try:
        clean_code = code.strip().upper()
        club = db.execute("SELECT * FROM clubs WHERE UPPER(code) = ?", (clean_code,)).fetchone()
        if not club:
            raise HTTPException(status_code=404, detail="Club playlist not found. Check the code and try again.")

        now_ms = int(time.time() * 1000)
        insert_member(db, club["id"], user_id, username, now_ms)
        db.commit()

        return {
            "success": True,
            "club_id": club["id"],
            "code": club["code"],
            "name": club["name"]
        }
    finally:
        db.close()


@router.get("/my-clubs")
def get_my_clubs(request: Request, user_id: int = Query(...)):
    """Returns all collaborative club playlists the user owns or joined."""
    db = get_db()
    try:
        base_url = get_base_url(request)
        rows = db.execute(
            """
            SELECT DISTINCT c.id, c.code, c.name, c.owner_user_id, c.owner_username, c.created_at,
                   (SELECT COUNT(DISTINCT m.user_id) FROM club_members m WHERE m.club_id = c.id) as member_count,
                   (SELECT COUNT(*) FROM club_songs cs WHERE cs.club_id = c.id) as song_count
            FROM clubs c
            JOIN club_members cm ON c.id = cm.club_id
            WHERE cm.user_id = ?
            ORDER BY c.id DESC
            """,
            (user_id,)
        ).fetchall()

        club_list = []
        for r in rows:
            # Fetch up to 4 cover previews for album collage
            cover_rows = db.execute(
                """
                SELECT s.cover_path FROM club_songs cs
                JOIN songs s ON cs.song_id = s.id
                WHERE cs.club_id = ?
                ORDER BY cs.position ASC
                LIMIT 4
                """,
                (r["id"],)
            ).fetchall()

            preview_covers = [
                f"{base_url}/covers/{os.path.basename(cr['cover_path'])}"
                for cr in cover_rows if cr["cover_path"]
            ]

            club_list.append({
                "club_id": r["id"],
                "code": r["code"],
                "name": r["name"],
                "owner_user_id": r["owner_user_id"],
                "owner_username": r["owner_username"],
                "is_owner": r["owner_user_id"] == user_id,
                "member_count": r["member_count"] or 1,
                "song_count": r["song_count"] or 0,
                "created_at": r["created_at"],
                "preview_covers": preview_covers
            })

        return {"success": True, "clubs": club_list}
    finally:
        db.close()


@router.get("/{club_id}")
def get_club_details(request: Request, club_id: int, user_id: Optional[int] = Query(None)):
    """Returns collaborative playlist details, members, and songs with collaborator attribution."""
    db = get_db()
    try:
        club = db.execute("SELECT * FROM clubs WHERE id = ?", (club_id,)).fetchone()
        if not club:
            raise HTTPException(status_code=404, detail="Club playlist not found")

        base_url = get_base_url(request)

        # Members
        member_rows = db.execute(
            """
            SELECT DISTINCT user_id, username
            FROM club_members
            WHERE club_id = ?
            ORDER BY (user_id = ?) DESC, username ASC
            """,
            (club_id, club["owner_user_id"])
        ).fetchall()

        members = [
            {
                "user_id": m["user_id"],
                "username": m["username"],
                "is_owner": m["user_id"] == club["owner_user_id"]
            }
            for m in member_rows
        ]

        # Songs
        song_rows = db.execute(
            """
            SELECT s.*, cs.added_by_user_id, cs.added_by_username, cs.added_at
            FROM club_songs cs
            JOIN songs s ON cs.song_id = s.id
            WHERE cs.club_id = ?
            ORDER BY cs.position ASC
            """,
            (club_id,)
        ).fetchall()

        song_dicts = []
        for r in song_rows:
            song_dicts.append({
                "id": r["id"],
                "title": r["title"],
                "artist": r["artist"],
                "audio_url": f"{base_url}/music/{os.path.basename(r['audio_path'])}",
                "cover_url": f"{base_url}/covers/{os.path.basename(r['cover_path'])}",
                "dominant_color": r["dominant_color"],
                "duration": r["duration"] or 0.0,
                "added_by_user_id": r["added_by_user_id"],
                "added_by_username": r["added_by_username"],
                "added_at": r["added_at"]
            })

        song_dicts = attach_artists_to_song_dicts(db, song_dicts, base_url)

        return {
            "success": True,
            "club_id": club["id"],
            "code": club["code"],
            "name": club["name"],
            "owner_user_id": club["owner_user_id"],
            "owner_username": club["owner_username"],
            "is_owner": (user_id is not None and user_id == club["owner_user_id"]),
            "members": members,
            "songs": song_dicts
        }
    finally:
        db.close()


@router.post("/add-song")
def add_song_to_club(
    request: Request,
    club_id: int = Form(...),
    user_id: int = Form(...),
    username: str = Form(...),
    song_id: int = Form(...)
):
    """Adds a song to the collaborative club playlist with contributor attribution."""
    db = get_db()
    try:
        club = db.execute("SELECT id FROM clubs WHERE id = ?", (club_id,)).fetchone()
        if not club:
            raise HTTPException(status_code=404, detail="Club playlist not found")

        song = db.execute("SELECT id FROM songs WHERE id = ?", (song_id,)).fetchone()
        if not song:
            raise HTTPException(status_code=404, detail="Song not found")

        # Ensure user is member
        now_ms = int(time.time() * 1000)
        insert_member(db, club_id, user_id, username, now_ms)

        # Get next position
        max_pos_row = db.execute("SELECT COALESCE(MAX(position), 0) as max_p FROM club_songs WHERE club_id = ?", (club_id,)).fetchone()
        next_pos = (max_pos_row["max_p"] or 0) + 1

        db.execute(
            """
            INSERT INTO club_songs (club_id, song_id, added_by_user_id, added_by_username, position)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(club_id, song_id) DO UPDATE SET
                added_by_user_id = EXCLUDED.added_by_user_id,
                added_by_username = EXCLUDED.added_by_username
            """,
            (club_id, song_id, user_id, username.strip(), next_pos)
        )
        db.commit()

        return {"success": True, "message": "Song added to club playlist"}
    finally:
        db.close()


@router.post("/remove-song")
def remove_song_from_club(
    request: Request,
    club_id: int = Form(...),
    user_id: int = Form(...),
    song_id: int = Form(...)
):
    """Removes a song from the collaborative playlist. Allowed for the song contributor or club owner."""
    db = get_db()
    try:
        club = db.execute("SELECT owner_user_id FROM clubs WHERE id = ?", (club_id,)).fetchone()
        if not club:
            raise HTTPException(status_code=404, detail="Club playlist not found")

        song_entry = db.execute(
            "SELECT added_by_user_id FROM club_songs WHERE club_id = ? AND song_id = ?",
            (club_id, song_id)
        ).fetchone()
        if not song_entry:
            return {"success": True, "message": "Song already removed"}

        is_owner = (club["owner_user_id"] == user_id)
        is_contributor = (song_entry["added_by_user_id"] == user_id)

        if not (is_owner or is_contributor):
            raise HTTPException(status_code=403, detail="Only the person who added this song or the playlist owner can remove it")

        db.execute("DELETE FROM club_songs WHERE club_id = ? AND song_id = ?", (club_id, song_id))
        db.commit()

        return {"success": True, "message": "Song removed from collaborative playlist"}
    finally:
        db.close()


@router.post("/leave")
def leave_club(
    request: Request,
    club_id: int = Form(...),
    user_id: int = Form(...)
):
    """Leaves a collaborative club playlist."""
    db = get_db()
    try:
        club = db.execute("SELECT * FROM clubs WHERE id = ?", (club_id,)).fetchone()
        if not club:
            return {"success": True, "message": "Club playlist does not exist"}

        if club["owner_user_id"] == user_id:
            # If owner leaves, check if there are other members to transfer to
            other_member = db.execute(
                "SELECT user_id, username FROM club_members WHERE club_id = ? AND user_id != ? LIMIT 1",
                (club_id, user_id)
            ).fetchone()
            if other_member:
                db.execute(
                    "UPDATE clubs SET owner_user_id = ?, owner_username = ? WHERE id = ?",
                    (other_member["user_id"], other_member["username"], club_id)
                )
                db.execute("DELETE FROM club_members WHERE club_id = ? AND user_id = ?", (club_id, user_id))
                db.commit()
                return {"success": True, "message": "Transferred ownership and left playlist"}
            else:
                delete_club_cascade(db, club_id)
                return {"success": True, "deleted": True, "message": "Playlist deleted as last owner left"}
        else:
            db.execute("DELETE FROM club_members WHERE club_id = ? AND user_id = ?", (club_id, user_id))
            db.commit()
            return {"success": True, "message": "Left collaborative playlist"}
    finally:
        db.close()


@router.post("/delete")
def delete_club(
    request: Request,
    club_id: int = Form(...),
    user_id: int = Form(...)
):
    """Permanently deletes a collaborative playlist. Allowed only for the owner."""
    db = get_db()
    try:
        club = db.execute("SELECT * FROM clubs WHERE id = ?", (club_id,)).fetchone()
        if not club:
            return {"success": True, "deleted": True}

        if club["owner_user_id"] == user_id:
            delete_club_cascade(db, club_id)
            return {"success": True, "deleted": True, "message": "Collaborative playlist permanently deleted"}
        else:
            raise HTTPException(status_code=403, detail="Only the creator can delete this collaborative playlist")
    finally:
        db.close()
