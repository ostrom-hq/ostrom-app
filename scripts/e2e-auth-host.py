#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""The E2E auth host: stands for GitHub and the server's start route in the E2E build's browser sign-in.

The E2E build sends the sign-in of a server of `browser.example` here (src/config/mockServices.ts), on the runner,
which the Android emulator reaches at 10.0.2.2:4700. The page has one link back to the app's `ostrom://auth/callback`,
tapped by the Maestro flow as a person taps "Authorize" on GitHub. The code is `pkce:<challenge>`: the in-memory server
takes it only with the verifier of that challenge.

Modes (`mode`, the first label of the server typed in the app):
- good: the sign-in's own state;
- forged: a state this app never drew;
- replay: the code and state of the last good sign-in, played again.
"""
import html
import http.server
import secrets
import sys
import urllib.parse

REDIRECT_URI = "ostrom://auth/callback"
last_good = {}


class Host(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        query = dict(urllib.parse.parse_qsl(url.query))
        if url.path != "/api/v1/auth/github/start" or query.get("redirect_uri") != REDIRECT_URI:
            return self.answer(404, "Not the sign-in start of the app")
        mode = query.get("mode", "")
        if mode == "good":
            back = {"code": "pkce:" + query.get("code_challenge", ""), "state": query.get("state", "")}
            last_good.clear()
            last_good.update(back)
        elif mode == "forged":
            back = {"code": "pkce:" + query.get("code_challenge", ""), "state": secrets.token_urlsafe(16)}
        elif mode == "replay" and last_good:
            back = dict(last_good)
        else:
            return self.answer(400, "Unknown mode, or nothing to replay: " + mode)
        link = REDIRECT_URI + "?" + urllib.parse.urlencode(back)
        print(f"e2e-auth-host: {mode} -> state {back['state']}", file=sys.stderr, flush=True)
        page = (
            "<!doctype html><meta name=viewport content='width=device-width'>"
            "<title>E2E auth host</title>"
            f"<p style='font-size:28px;padding:48px'><a href='{html.escape(link)}'>Back to Ostrom</a></p>"
        )
        return self.answer(200, page, "text/html; charset=utf-8")

    def answer(self, status, body, content_type="text/plain; charset=utf-8"):
        data = body.encode()
        self.send_response(status)
        self.send_header("content-type", content_type)
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format, *args):
        print("e2e-auth-host: " + format % args, file=sys.stderr, flush=True)


if __name__ == "__main__":
    http.server.ThreadingHTTPServer(("0.0.0.0", 4700), Host).serve_forever()
