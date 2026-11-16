from fastapi import APIRouter, UploadFile, File, Form, Query, Request, HTTPException
from fastapi.responses import StreamingResponse, Response, JSONResponse
from typing import Optional, List
from database import get_db, MUSIC_DIR, COVER_DIR, allot_song_artists, attach_artists_to_song_dicts
import uuid
import os
import hashlib
import mimetypes
import struct
import io
import json

router = APIRouter(prefix="/songs", tags=["songs"])


def _parse_id_list(raw: Optional[str]) -> List[int]:
    if not raw or not str(raw).strip():
        return []
    text = str(raw).strip()
    if text.startswith("["):
        try:
            arr = json.loads(text)
            if isinstance(arr, list):
                return [int(x) for x in arr if str(x).strip().isdigit()]
        except Exception:
            pass
    out = []
    for part in text.split(","):
        p = part.strip()
        if p.isdigit():
            out.append(int(p))
    return out


def _parse_name_list(raw: Optional[str]) -> List[str]:
    if not raw or not str(raw).strip():
        return []
    text = str(raw).strip()
    if text.startswith("["):
        try:
            arr = json.loads(text)
            if isinstance(arr, list):
                return [str(x).strip() for x in arr if str(x).strip()]
        except Exception:
            pass
    return [p.strip() for p in text.split(",") if p.strip()]


def _catalog_artist_sig(db) -> str:
    sa = db.execute("SELECT COUNT(*) AS cnt, COALESCE(MAX(artist_id), 0) AS max_aid FROM song_artists").fetchone()
    ar = db.execute("SELECT COUNT(*) AS cnt, COALESCE(MAX(updated_at), '') AS max_up FROM artists").fetchone()
    return f"{sa['cnt'] if sa else 0}:{sa['max_aid'] if sa else 0}:{ar['cnt'] if ar else 0}:{ar['max_up'] if ar else ''}"

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

def calculate_duration(file_path: str) -> float:
    try:
        from mutagen import File as MutagenFile
        audio_info = MutagenFile(file_path)
        if audio_info and audio_info.info and hasattr(audio_info.info, "length"):
            return round(float(audio_info.info.length), 2)
    except Exception:
        pass
    return 0.0


def sanitize_and_inject_color_metadata(image_bytes: bytes, ext: str, color_hex: str) -> bytes:
    clean_hex = (color_hex or "").strip().upper()
    if not clean_hex.startswith("#"):
        clean_hex = "#" + clean_hex
    if len(clean_hex) != 7 or not all(c in "0123456789ABCDEF" for c in clean_hex[1:]):
        clean_hex = "#00E599"

    meta_payload = f"DOMINANT_COLOR:{clean_hex}"

    try:
        from PIL import Image, ImageOps
        img = ImageOps.exif_transpose(Image.open(io.BytesIO(image_bytes)))
        out_buf = io.BytesIO()
        lower_ext = ext.lower()
        target_fmt = "JPEG" if lower_ext in (".jpg", ".jpeg") else ("PNG" if lower_ext == ".png" else (img.format or "JPEG"))

        if target_fmt == "JPEG":
            if img.mode not in ("RGB", "L"):
                img = img.convert("RGB")
            exif = img.getexif()
            exif.clear()
            exif[0x010E] = meta_payload
            exif[0x0131] = f"AlaktraMusic {meta_payload}"
            exif[0x9286] = b"ASCII\x00\x00\x00" + meta_payload.encode("ascii")
            img.save(out_buf, format="JPEG", quality=95, exif=exif)
            return out_buf.getvalue()
        elif target_fmt == "PNG":
            from PIL.PngImagePlugin import PngInfo
            target_info = PngInfo()
            target_info.add_text("Comment", meta_payload)
            target_info.add_text("Description", meta_payload)
            target_info.add_text("Software", f"AlaktraMusic {meta_payload}")
            img.save(out_buf, format="PNG", pnginfo=target_info)
            return out_buf.getvalue()
    except Exception:
        pass

    if image_bytes.startswith(b"\xFF\xD8"):
        try:
            strip_markers = {0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xEB, 0xEC, 0xED, 0xEE, 0xEF, 0xFE}
            ascii_bytes = meta_payload.encode("utf-8") + b"\x00"
            str_len = len(ascii_bytes)
            offset_str1 = 38
            offset_str2 = 38 + str_len
            entry1 = struct.pack("<HHII", 0x010E, 2, str_len, offset_str1)
            entry2 = struct.pack("<HHII", 0x0131, 2, str_len, offset_str2)
            ifd0 = struct.pack("<H", 2) + entry1 + entry2 + struct.pack("<I", 0)
            tiff = b"II\x2a\x00" + struct.pack("<I", 8) + ifd0 + ascii_bytes + ascii_bytes
            app1_payload = b"Exif\x00\x00" + tiff
            app1_seg = b"\xFF\xE1" + struct.pack(">H", len(app1_payload) + 2) + app1_payload

            out = [b"\xFF\xD8", app1_seg]
            i = 2
            while i < len(image_bytes) - 1:
                if image_bytes[i] != 0xFF:
                    out.append(image_bytes[i:])
                    break
                marker = image_bytes[i + 1]
                i += 2
                if marker in (0xD8, 0xD9):
                    out.append(bytes([0xFF, marker]))
                    continue
                if marker == 0xDA:
                    out.append(bytes([0xFF, marker]))
                    out.append(image_bytes[i:])
                    break
                if i + 2 > len(image_bytes):
                    break
                seg_len = struct.unpack(">H", image_bytes[i:i + 2])[0]
                if marker not in strip_markers:
                    out.append(bytes([0xFF, marker]))
                    out.append(image_bytes[i:i + seg_len])
                i += seg_len
            return b"".join(out)
        except Exception:
            return image_bytes

    return image_bytes


@router.get("")
def get_songs(request: Request):
    db = get_db()
    try:
        songs = db.execute("SELECT * FROM songs ORDER BY id DESC").fetchall()
        base_url = get_base_url(request)

        max_song_up = max((str(s["updated_at"] or "") for s in songs), default="")
        latest_token = f"{len(songs)}_{songs[0]['id'] if songs else 0}_{max_song_up}_{_catalog_artist_sig(db)}"
        etag = f'"{hashlib.md5(latest_token.encode()).hexdigest()}"'
        client_etag = request.headers.get("if-none-match")
        if client_etag and client_etag.strip('"') == etag.strip('"'):
            return Response(status_code=304, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})

        data = [
            {
                "id": s["id"],
                "title": s["title"],
                "artist": s["artist"],
                "audio_url": f"{base_url}/songs/{s['id']}/stream",
                "cover_url": f"{base_url}/{s['cover_path'].replace(os.sep, '/')}",
                "dominant_color": s["dominant_color"] or "#00E599",
                "duration": float(s["duration"]) if s["duration"] else 0.0,
            }
            for s in songs
        ]
        attach_artists_to_song_dicts(db, data, base_url)
        return JSONResponse(content=data, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})
    finally:
        db.close()


@router.api_route(
    "/{song_id}/stream",
    methods=["GET", "HEAD"]
)
def stream_song(song_id: int, request: Request):
    db = get_db()
    try:
        song = db.execute(
            "SELECT audio_path, etag, updated_at FROM songs WHERE id = ?",
            (song_id,),
        ).fetchone()

        if not song:
            raise HTTPException(status_code=404, detail="Song not found")

        file_path = song["audio_path"]
        if not os.path.isabs(file_path):
            file_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), file_path)

        if not os.path.exists(file_path):
            raise HTTPException(status_code=404, detail="Audio file not found on disk")

        file_size = os.path.getsize(file_path)
        etag = song["etag"]
        updated_at_str = str(song["updated_at"] or "")

        if not etag:
            with open(file_path, "rb") as f:
                file_hash = hashlib.md5(f.read()).hexdigest()
            db.execute("UPDATE songs SET etag=? WHERE id=?", (file_hash, song_id))
            db.commit()
            etag = file_hash
    finally:
        db.close()

    content_type, _ = mimetypes.guess_type(file_path)
    if not content_type:
        content_type = "audio/mpeg"

    range_header = request.headers.get("range") or request.headers.get("Range")

    if request.method == "HEAD":
        return StreamingResponse(
            iter([]),
            status_code=200,
            headers={
                "Accept-Ranges": "bytes",
                "Content-Length": str(file_size),
                "Content-Type": content_type,
                "ETag": etag,
            }
        )

    def iterfile(start: int, end: int, chunk_size: int = 16384):
        with open(file_path, "rb") as f:
            f.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                read_bytes = min(chunk_size, remaining)
                data = f.read(read_bytes)
                if not data:
                    break
                remaining -= len(data)
                yield data

    if range_header and range_header.startswith("bytes="):
        try:
            bytes_range = range_header.replace("bytes=", "").strip()
            parts = bytes_range.split("-")
            start_str, end_str = parts[0].strip(), parts[1].strip()

            if start_str and int(start_str) >= file_size:
                return StreamingResponse(
                    iter([]),
                    status_code=416,
                    headers={
                        "Content-Range": f"bytes */{file_size}",
                        "Accept-Ranges": "bytes",
                    }
                )

            if start_str and end_str:
                start = int(start_str)
                end = min(int(end_str), file_size - 1)
            elif start_str:
                start = int(start_str)
                end = file_size - 1
            elif end_str:
                length = int(end_str)
                start = max(0, file_size - length)
                end = file_size - 1
            else:
                start = 0
                end = file_size - 1

            start = max(0, min(start, file_size - 1))
            end = max(start, min(end, file_size - 1))
            chunk_length = end - start + 1

            headers = {
                "Content-Range": f"bytes {start}-{end}/{file_size}",
                "Accept-Ranges": "bytes",
                "Content-Length": str(chunk_length),
                "Content-Type": content_type,
                "ETag": etag,
                "Last-Modified": updated_at_str,
            }

            return StreamingResponse(
                iterfile(start, end),
                status_code=206,
                headers=headers,
            )
        except Exception:
            pass

    headers = {
        "Accept-Ranges": "bytes",
        "Content-Length": str(file_size),
        "Content-Type": content_type,
        "ETag": etag,
    }
    return StreamingResponse(
        iterfile(0, file_size - 1) if file_size > 0 else iter([]),
        media_type=content_type,
        headers=headers,
    )


@router.post("/upload")
async def upload_song(
    request: Request,
    title: str = Form(...),
    artist: str = Form(""),
    audio: UploadFile = File(...),
    cover: UploadFile = File(...),
    color: str = Form("#00E599"),
    primary_artist_id: Optional[int] = Form(None),
    primary_artist_name: Optional[str] = Form(None),
    featured_artist_ids: Optional[str] = Form(None),
    featured_artist_names: Optional[str] = Form(None),
    uploader_user_id: Optional[int] = Form(None),
):
    clean_title = (title or "").strip()
    if not clean_title:
        raise HTTPException(status_code=400, detail="Song title is required")

    db = get_db()
    try:
        base_url = get_base_url(request)

        effective_primary_id = primary_artist_id
        effective_primary_name = (primary_artist_name or "").strip() or None
        if uploader_user_id is not None:
            claimed = db.execute(
                "SELECT id, name FROM artists WHERE claimed_by_user_id = ?",
                (int(uploader_user_id),)
            ).fetchone()
            if not claimed:
                raise HTTPException(
                    status_code=403,
                    detail="Only verified claimed artist accounts can release tracks from Artist Studio."
                )
            effective_primary_id = int(claimed["id"])
            effective_primary_name = claimed["name"]

        if (
            (effective_primary_id is None or int(effective_primary_id) <= 0)
            and not effective_primary_name
            and not (artist or "").strip()
        ):
            raise HTTPException(status_code=400, detail="Primary artist is required")

        audio_ext = os.path.splitext(audio.filename or "")[1].lower() or ".mp3"
        cover_ext = os.path.splitext(cover.filename or "")[1].lower() or ".jpg"

        audio_filename = f"{uuid.uuid4()}{audio_ext}"
        cover_filename = f"{uuid.uuid4()}{cover_ext}"

        audio_path = os.path.join(MUSIC_DIR, audio_filename)
        cover_path = os.path.join(COVER_DIR, cover_filename)

        audio_bytes = await audio.read()
        raw_cover_bytes = await cover.read()

        clean_color = (color or "").strip().upper()
        if not clean_color.startswith("#"):
            clean_color = "#" + clean_color
        if len(clean_color) != 7 or not all(c in "0123456789ABCDEF" for c in clean_color[1:]):
            clean_color = "#00E599"

        sanitized_cover_bytes = sanitize_and_inject_color_metadata(raw_cover_bytes, cover_ext, clean_color)

        with open(audio_path, "wb") as f:
            f.write(audio_bytes)

        with open(cover_path, "wb") as f:
            f.write(sanitized_cover_bytes)

        rel_audio_path = os.path.join("music", audio_filename).replace(os.sep, "/")
        rel_cover_path = os.path.join("covers", cover_filename).replace(os.sep, "/")

        etag = hashlib.md5(audio_bytes).hexdigest()
        duration = calculate_duration(audio_path)

        initial_artist_label = effective_primary_name or (artist or "").strip() or "Unknown Artist"
        cursor = db.execute(
            """
            INSERT INTO songs (title, artist, audio_path, cover_path, dominant_color, etag, duration, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))
            """,
            (clean_title, initial_artist_label, rel_audio_path, rel_cover_path, clean_color, etag, duration),
        )
        new_song_id = int(cursor.lastrowid)

        display_artist = allot_song_artists(
            db,
            song_id=new_song_id,
            primary_artist_id=effective_primary_id,
            primary_artist_name=effective_primary_name,
            featured_artist_ids=_parse_id_list(featured_artist_ids),
            featured_artist_names=_parse_name_list(featured_artist_names),
            fallback_artist_string=(artist or "").strip(),
        )
        db.commit()

        row = db.execute("SELECT * FROM songs WHERE id = ?", (new_song_id,)).fetchone()
        song_dict = {
            "id": row["id"],
            "title": row["title"],
            "artist": row["artist"],
            "audio_url": f"{base_url}/songs/{row['id']}/stream",
            "cover_url": f"{base_url}/{row['cover_path'].replace(os.sep, '/')}",
            "dominant_color": row["dominant_color"] or "#00E599",
            "duration": float(row["duration"]) if row["duration"] else 0.0,
        }
        attach_artists_to_song_dicts(db, [song_dict], base_url)

        return {
            "success": True,
            "id": new_song_id,
            "title": clean_title,
            "artist": display_artist,
            "dominant_color": clean_color,
            "song": song_dict,
        }
    finally:
        db.close()


@router.get("/search")
def search_songs(
    request: Request,
    q: str = Query("", description="Search query"),
    limit: int = Query(30, ge=1, le=100),
    offset: int = Query(0, ge=0),
):
    db = get_db()
    try:
        clean_q = q.strip()
        query = f"%{clean_q}%"

        songs = db.execute(
            """
            SELECT *
            FROM songs
            WHERE title LIKE ?
               OR artist LIKE ?
               OR EXISTS (
                   SELECT 1
                   FROM song_artists sa
                   JOIN artists a ON a.id = sa.artist_id
                   WHERE sa.song_id = songs.id AND a.name LIKE ?
               )
            ORDER BY id DESC
            LIMIT ? OFFSET ?
            """,
            (query, query, query, limit, offset),
        ).fetchall()

        base_url = get_base_url(request)

        max_song_up = max((str(s["updated_at"] or "") for s in songs), default="")
        query_sig = f"{clean_q}_{limit}_{offset}_{len(songs)}_{songs[0]['id'] if songs else 0}_{max_song_up}_{_catalog_artist_sig(db)}"
        etag = f'"{hashlib.md5(query_sig.encode()).hexdigest()}"'
        client_etag = request.headers.get("if-none-match")
        if client_etag and client_etag.strip('"') == etag.strip('"'):
            return Response(status_code=304, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})

        data = [
            {
                "id": s["id"],
                "title": s["title"],
                "artist": s["artist"],
                "audio_url": f"{base_url}/songs/{s['id']}/stream",
                "cover_url": f"{base_url}/{s['cover_path'].replace(os.sep, '/')}",
                "dominant_color": s["dominant_color"] or "#00E599",
                "duration": float(s["duration"]) if s["duration"] else 0.0,
            }
            for s in songs
        ]
        attach_artists_to_song_dicts(db, data, base_url)
        return JSONResponse(content=data, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})
    finally:
        db.close()


@router.post("/{song_id}/allot")
def allot_existing_song_artists(
    song_id: int,
    request: Request,
    title: Optional[str] = Form(None),
    color: Optional[str] = Form(None),
    primary_artist_id: Optional[int] = Form(None),
    primary_artist_name: Optional[str] = Form(None),
    featured_artist_ids: Optional[str] = Form(None),
    featured_artist_names: Optional[str] = Form(None),
    requester_user_id: Optional[int] = Form(None),
):
    db = get_db()
    try:
        base_url = get_base_url(request)
        song = db.execute("SELECT * FROM songs WHERE id = ?", (song_id,)).fetchone()
        if not song:
            raise HTTPException(status_code=404, detail="Song not found")

        if requester_user_id is not None:
            owner_check = db.execute(
                """
                SELECT a.claimed_by_user_id
                FROM song_artists sa
                JOIN artists a ON a.id = sa.artist_id
                WHERE sa.song_id = ? AND sa.role = 'primary'
                LIMIT 1
                """,
                (song_id,)
            ).fetchone()
            if not owner_check or owner_check["claimed_by_user_id"] is None or int(owner_check["claimed_by_user_id"]) != int(requester_user_id):
                raise HTTPException(status_code=403, detail="Only the primary artist's verified account can edit this track.")

        new_title = song["title"]
        if title is not None and title.strip():
            new_title = title.strip()

        clean_color = song["dominant_color"] or "#00E599"
        if color is not None and color.strip():
            cand = color.strip().upper()
            if not cand.startswith("#"):
                cand = "#" + cand
            if len(cand) == 7 and all(c in "0123456789ABCDEF" for c in cand[1:]):
                clean_color = cand
                if clean_color != (song["dominant_color"] or "").upper():
                    cover_rel = song["cover_path"]
                    cover_abs = os.path.join(os.path.dirname(os.path.dirname(__file__)), cover_rel)
                    if os.path.exists(cover_abs):
                        ext = os.path.splitext(cover_abs)[1].lower() or ".jpg"
                        try:
                            with open(cover_abs, "rb") as f:
                                raw_bytes = f.read()
                            sanitized_bytes = sanitize_and_inject_color_metadata(raw_bytes, ext, clean_color)
                            with open(cover_abs, "wb") as f:
                                f.write(sanitized_bytes)
                        except Exception as e:
                            print(f"⚠️ Error updating cover image file on disk: {e}")

        db.execute(
            "UPDATE songs SET title = ?, dominant_color = ?, updated_at = datetime('now') WHERE id = ?",
            (new_title, clean_color, song_id)
        )

        if (
            (primary_artist_id is not None and int(primary_artist_id) > 0)
            or (primary_artist_name is not None and primary_artist_name.strip())
            or featured_artist_ids is not None
            or featured_artist_names is not None
        ):
            eff_primary_id = primary_artist_id
            if (eff_primary_id is None or int(eff_primary_id) <= 0) and not (primary_artist_name or "").strip():
                cur_prim = db.execute(
                    "SELECT artist_id FROM song_artists WHERE song_id = ? AND role = 'primary' LIMIT 1",
                    (song_id,)
                ).fetchone()
                if cur_prim:
                    eff_primary_id = int(cur_prim["artist_id"])

            allot_song_artists(
                db,
                song_id=song_id,
                primary_artist_id=eff_primary_id,
                primary_artist_name=primary_artist_name,
                featured_artist_ids=_parse_id_list(featured_artist_ids),
                featured_artist_names=_parse_name_list(featured_artist_names),
                fallback_artist_string=song["artist"],
            )

        db.commit()
        updated = db.execute("SELECT * FROM songs WHERE id = ?", (song_id,)).fetchone()
        song_dict = {
            "id": updated["id"],
            "title": updated["title"],
            "artist": updated["artist"],
            "audio_url": f"{base_url}/songs/{updated['id']}/stream",
            "cover_url": f"{base_url}/{updated['cover_path'].replace(os.sep, '/')}",
            "dominant_color": updated["dominant_color"] or "#00E599",
            "duration": float(updated["duration"]) if updated["duration"] else 0.0,
        }
        attach_artists_to_song_dicts(db, [song_dict], base_url)
        return {"success": True, "song": song_dict}
    finally:
        db.close()


@router.post("/{song_id}/update-cover-metadata")
def update_song_cover_metadata(song_id: int, color: str = Form(...)):
    db = get_db()
    try:
        song = db.execute("SELECT * FROM songs WHERE id = ?", (song_id,)).fetchone()
        if not song:
            raise HTTPException(status_code=404, detail="Song not found")

        clean_color = (color or "").strip().upper()
        if not clean_color.startswith("#"):
            clean_color = "#" + clean_color
        if len(clean_color) != 7 or not all(c in "0123456789ABCDEF" for c in clean_color[1:]):
            clean_color = "#00E599"

        cover_rel = song["cover_path"]
        cover_abs = os.path.join(os.path.dirname(os.path.dirname(__file__)), cover_rel)
        if os.path.exists(cover_abs):
            ext = os.path.splitext(cover_abs)[1].lower() or ".jpg"
            try:
                with open(cover_abs, "rb") as f:
                    raw_bytes = f.read()
                sanitized_bytes = sanitize_and_inject_color_metadata(raw_bytes, ext, clean_color)
                with open(cover_abs, "wb") as f:
                    f.write(sanitized_bytes)
            except Exception as e:
                print(f"⚠️ Error updating cover image file on disk: {e}")

        db.execute(
            "UPDATE songs SET dominant_color = ?, updated_at = datetime('now') WHERE id = ?",
            (clean_color, song_id)
        )
        db.commit()

        return {
            "success": True,
            "id": song_id,
            "title": song["title"],
            "dominant_color": clean_color
        }
    finally:
        db.close()


@router.post("/batch-update-cover-metadata")
async def batch_update_cover_metadata(request: Request):
    payload = await request.json()
    items = payload if isinstance(payload, list) else payload.get("items", [])
    db = get_db()
    try:
        results = []

        for item in items:
            song_id = item.get("song_id") or item.get("id")
            color = item.get("color", "#00E599")
            if not song_id:
                continue

            song = db.execute("SELECT * FROM songs WHERE id = ?", (song_id,)).fetchone()
            if not song:
                continue

            clean_color = (color or "").strip().upper()
            if not clean_color.startswith("#"):
                clean_color = "#" + clean_color
            if len(clean_color) != 7 or not all(c in "0123456789ABCDEF" for c in clean_color[1:]):
                clean_color = "#00E599"

            cover_rel = song["cover_path"]
            cover_abs = os.path.join(os.path.dirname(os.path.dirname(__file__)), cover_rel)
            if os.path.exists(cover_abs):
                ext = os.path.splitext(cover_abs)[1].lower() or ".jpg"
                try:
                    with open(cover_abs, "rb") as f:
                        raw_bytes = f.read()
                    sanitized_bytes = sanitize_and_inject_color_metadata(raw_bytes, ext, clean_color)
                    with open(cover_abs, "wb") as f:
                        f.write(sanitized_bytes)
                except Exception as e:
                    print(f"⚠️ Error updating cover image file for song {song_id}: {e}")

            db.execute(
                "UPDATE songs SET dominant_color = ?, updated_at = datetime('now') WHERE id = ?",
                (clean_color, song_id)
            )
            results.append({"id": song_id, "title": song["title"], "dominant_color": clean_color})

        db.commit()
        return {"success": True, "updated_count": len(results), "results": results}
    finally:
        db.close()
