"""S0 OCR service health endpoint; recognition arrives with S1/S4 models."""

from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self.respond(200, {"status": "UP", "recognition": "UNAVAILABLE"})
        else:
            self.respond(404, {"error": "not_found"})

    def do_POST(self):
        if self.path == "/recognize":
            self.respond(501, {"error": "recognition_not_configured"})
        else:
            self.respond(404, {"error": "not_found"})

    def respond(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", int(os.environ.get("OCR_PORT", "8090"))), Handler).serve_forever()
