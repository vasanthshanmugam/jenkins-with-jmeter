"""
ShopLite API - a tiny, dependency-free e-commerce API used as the System Under Test
for the JMeter + Jenkins lab. Standard library only (Python 3.9+).

Endpoints
  GET  /health                     -> {"status":"UP"}
  POST /api/login                  -> {"token": "..."}         body: {"username","password"}
  GET  /api/products               -> [{"id","name","price"}]  header: Authorization: Bearer <token>
  GET  /api/products/{id}          -> {"id","name","price","stock"}
  POST /api/orders                 -> 201 {"orderId","status"} body: {"productId","quantity"}
  GET  /api/orders/{orderId}       -> {"orderId","productId","quantity","status"}

Chaos controls (used for the failure-scenario exercises in docs/10)
  GET  /admin/chaos                -> current settings
  POST /admin/chaos                body: {"delayMs": 0, "errorRate": 0.0, "endpoint": "all"}
  Initial values can also come from env vars CHAOS_DELAY_MS / CHAOS_ERROR_RATE.

Test users: user01 .. user20, password "Passw0rd!" (lab-only test data, not a secret).
"""
import json
import os
import random
import re
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("APP_PORT", "8080"))

USERS = {f"user{i:02d}": "Passw0rd!" for i in range(1, 21)}
PRODUCTS = {
    i: {"id": i, "name": f"Product {i:03d}", "price": round(5 + i * 1.75, 2), "stock": 1000}
    for i in range(1, 51)
}

_lock = threading.Lock()
_tokens = {}           # token -> username
_orders = {}           # orderId -> order
_order_seq = [1000]
_chaos = {
    "delayMs": int(os.environ.get("CHAOS_DELAY_MS", "0")),
    "errorRate": float(os.environ.get("CHAOS_ERROR_RATE", "0")),
    "endpoint": "all",  # "all" or a path prefix such as "/api/orders"
}


class Handler(BaseHTTPRequestHandler):
    server_version = "ShopLite/1.0"
    protocol_version = "HTTP/1.1"

    # ---------- helpers ----------
    def log_message(self, fmt, *args):  # keep container logs quiet; errors still logged
        pass

    def _send(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length))
        except ValueError:
            return None

    def _user(self):
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return None
        with _lock:
            return _tokens.get(auth[7:].strip())

    def _apply_chaos(self):
        """Returns True if the request was short-circuited with an injected error."""
        if self.path.startswith("/admin") or self.path == "/health":
            return False
        c = dict(_chaos)
        if c["endpoint"] != "all" and not self.path.startswith(c["endpoint"]):
            return False
        if c["delayMs"] > 0:
            # +/-20% jitter so percentiles look realistic
            time.sleep(c["delayMs"] * random.uniform(0.8, 1.2) / 1000.0)
        if c["errorRate"] > 0 and random.random() < c["errorRate"]:
            self._send(500, {"error": "Injected failure (chaos)"})
            return True
        return False

    # ---------- routing ----------
    def do_GET(self):
        # small baseline service time so the dashboard is not all zeros
        time.sleep(random.uniform(0.005, 0.030))
        if self.path == "/health":
            return self._send(200, {"status": "UP"})
        if self.path == "/admin/chaos":
            return self._send(200, _chaos)
        if self._apply_chaos():
            return
        if self.path == "/api/products":
            if not self._user():
                return self._send(401, {"error": "Missing or invalid token"})
            return self._send(200, [{k: p[k] for k in ("id", "name", "price")} for p in PRODUCTS.values()])
        m = re.fullmatch(r"/api/products/(\d+)", self.path)
        if m:
            if not self._user():
                return self._send(401, {"error": "Missing or invalid token"})
            p = PRODUCTS.get(int(m.group(1)))
            return self._send(200, p) if p else self._send(404, {"error": "Product not found"})
        m = re.fullmatch(r"/api/orders/(\d+)", self.path)
        if m:
            if not self._user():
                return self._send(401, {"error": "Missing or invalid token"})
            with _lock:
                o = _orders.get(int(m.group(1)))
            return self._send(200, o) if o else self._send(404, {"error": "Order not found"})
        self._send(404, {"error": "Not found"})

    def do_POST(self):
        time.sleep(random.uniform(0.010, 0.040))
        body = self._body()
        if body is None:
            return self._send(400, {"error": "Body must be valid JSON"})
        if self.path == "/admin/chaos":
            with _lock:
                if "delayMs" in body:
                    _chaos["delayMs"] = max(0, int(body["delayMs"]))
                if "errorRate" in body:
                    _chaos["errorRate"] = min(1.0, max(0.0, float(body["errorRate"])))
                if "endpoint" in body:
                    _chaos["endpoint"] = str(body["endpoint"])
            return self._send(200, _chaos)
        if self._apply_chaos():
            return
        if self.path == "/api/login":
            u, p = body.get("username"), body.get("password")
            if USERS.get(u) != p:
                return self._send(401, {"error": "Invalid credentials"})
            token = secrets.token_hex(16)
            with _lock:
                _tokens[token] = u
            return self._send(200, {"token": token, "username": u})
        if self.path == "/api/orders":
            user = self._user()
            if not user:
                return self._send(401, {"error": "Missing or invalid token"})
            try:
                pid, qty = int(body["productId"]), int(body.get("quantity", 1))
            except (KeyError, TypeError, ValueError):
                return self._send(400, {"error": "productId and quantity are required"})
            if pid not in PRODUCTS or qty < 1:
                return self._send(400, {"error": "Invalid productId or quantity"})
            with _lock:
                _order_seq[0] += 1
                oid = _order_seq[0]
                _orders[oid] = {"orderId": oid, "productId": pid, "quantity": qty,
                                "status": "CONFIRMED", "user": user}
            return self._send(201, {"orderId": oid, "status": "CONFIRMED"})
        self._send(404, {"error": "Not found"})


if __name__ == "__main__":
    print(f"ShopLite API listening on 0.0.0.0:{PORT} (chaos={_chaos})", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
