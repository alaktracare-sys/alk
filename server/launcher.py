#!/usr/bin/env python3
"""
Alaktra Music - 1-click launcher for the Admin Console.

    python launcher.py                 open the console on http://localhost:8080
    python launcher.py --no-browser    don't open a browser tab
    python launcher.py --start-api     also start the streaming API (port 8000)
                                       and the Cloudflare tunnel right away

The console runs on its own port (ADMIN_PORT, default 8080) and does NOT need
the API to be running: it reads music.db directly, so you can browse and edit
your library offline, then press the power button to bring the API (and the
tunnel) online.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
os.chdir(HERE)


def _reexec_in_venv():
    """If the project has its own venv, use it (the API and song uploads need it)."""
    names = (["venv/Scripts/python.exe", ".venv/Scripts/python.exe"] if os.name == "nt"
             else ["venv/bin/python", ".venv/bin/python"])
    for n in names:
        py = os.path.join(HERE, n)
        venv_root = os.path.dirname(os.path.dirname(py))
        if os.path.isfile(py) and os.path.realpath(sys.prefix) != os.path.realpath(venv_root):
            if os.environ.get("ALAKTRA_REEXEC") != "1":
                os.environ["ALAKTRA_REEXEC"] = "1"
                os.execv(py, [py] + sys.argv)
            break


if __name__ == "__main__":
    _reexec_in_venv()
    sys.path.insert(0, HERE)
    import admin_server

    admin_server.main(open_browser="--no-browser" not in sys.argv,
                      start_api="--start-api" in sys.argv)
