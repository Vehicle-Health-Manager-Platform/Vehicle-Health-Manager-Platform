"""Test-only byte forwarding to the fixed private OCR endpoint; no Docker API."""
import select
import socket
import socketserver
import time


class Forward(socketserver.BaseRequestHandler):
    def handle(self):
        with socket.create_connection(('ocr', 8090), timeout=5) as upstream:
            peers = (self.request, upstream)
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                readable, _, _ = select.select(peers, [], [], 1)
                for source in readable:
                    data = source.recv(65536)
                    if not data:
                        return
                    (upstream if source is self.request else self.request).sendall(data)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 8


if __name__ == '__main__':
    with Server(('0.0.0.0', 8091), Forward) as server:
        server.serve_forever()
