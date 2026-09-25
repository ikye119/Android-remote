"use strict";
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const HOST = process.env.RELAY_HOST || "127.0.0.1";
const PORT = Number(process.env.RELAY_PORT || 8080);
const PHONE_TOKEN = process.env.PHONE_TOKEN || "";
const VIEWER_TOKEN = process.env.VIEWER_TOKEN || "";
if (!PHONE_TOKEN || !VIEWER_TOKEN || PHONE_TOKEN === VIEWER_TOKEN ||
    PHONE_TOKEN.length < 32 || VIEWER_TOKEN.length < 32) {
  console.error("Set distinct PHONE_TOKEN and VIEWER_TOKEN (32+ characters each).");
  process.exit(1);
}

const peers = { phone: null, viewer: null };
const html = fs.readFileSync(path.join(__dirname, "dashboard.html"));
const server = http.createServer((req, res) => {
  if (req.method === "GET" && req.url === "/") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "Content-Security-Policy": "default-src 'self'; connect-src 'self' ws: wss:; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; base-uri 'none'; form-action 'none'", "X-Content-Type-Options": "nosniff" });
    res.end(html);
  } else { res.writeHead(404); res.end(); }
});

function matches(a, b) {
  const aa = Buffer.from(a), bb = Buffer.from(b);
  return aa.length === bb.length && crypto.timingSafeEqual(aa, bb);
}
function frame(op, payload) {
  const size = payload.length;
  const h = Buffer.alloc(size < 126 ? 2 : size <= 65535 ? 4 : 10);
  h[0] = 0x80 | op;
  if (size < 126) h[1] = size;
  else if (size <= 65535) { h[1] = 126; h.writeUInt16BE(size, 2); }
  else { h[1] = 127; h.writeBigUInt64BE(BigInt(size), 2); }
  return Buffer.concat([h, payload]);
}
function send(peer, op, data) {
  if (!peer || peer.socket.destroyed) return;
  if (peer.socket.writableLength > 2 * 1024 * 1024) return; // drop stale frames
  peer.socket.write(frame(op, Buffer.isBuffer(data) ? data : Buffer.from(data)));
}
function message(peer, obj) { send(peer, 1, JSON.stringify(obj)); }
function close(peer, code = 1000) {
  if (peer.socket.destroyed) return;
  const reason = Buffer.alloc(2); reason.writeUInt16BE(code);
  peer.socket.end(frame(8, reason));
  setTimeout(() => peer.socket.destroy(), 1000).unref();
}
function disconnect(peer) {
  if (peer.done) return;
  peer.done = true;
  if (peer.role && peers[peer.role] === peer) {
    peers[peer.role] = null;
    const other = peers[peer.role === "phone" ? "viewer" : "phone"];
    if (other) message(other, { type: "peer", connected: false });
  }
}
function packet(peer, op, payload) {
  if (op === 8) { close(peer); return; }
  if (op === 9) { send(peer, 10, payload); return; }
  if (op === 10) return;
  if (op !== 1 && op !== 2) { close(peer, 1003); return; }
  if (!peer.role) {
    if (op !== 1 || payload.length > 512) { close(peer, 1008); return; }
    let auth;
    try { auth = JSON.parse(payload.toString("utf8")); } catch { close(peer, 1008); return; }
    if (!auth || (auth.role !== "phone" && auth.role !== "viewer") ||
        typeof auth.token !== "string" || !matches(auth.token, auth.role === "phone" ? PHONE_TOKEN : VIEWER_TOKEN) ||
        peers[auth.role]) { close(peer, 1008); return; }
    peer.role = auth.role;
    peers[peer.role] = peer;
    const other = peers[peer.role === "phone" ? "viewer" : "phone"];
    message(peer, { type: "ready", peerConnected: !!other });
    if (other) message(other, { type: "peer", connected: true });
    return;
  }
  const other = peers[peer.role === "phone" ? "viewer" : "phone"];
  if (!other) return;
  if (peer.role === "phone" && op === 2 && payload.length <= 450000) {
    send(other, 2, payload); return;
  }
  if (op !== 1 || payload.length > 1024) return;
  let data;
  try { data = JSON.parse(payload.toString("utf8")); } catch { return; }
  if (peer.role === "phone" && data.type === "status" && typeof data.sharing === "boolean") {
    message(other, { type: "status", sharing: data.sharing });
  } else if (peer.role === "viewer" &&
      (data.type === "tap" || data.type === "swipe" || data.type === "back" || data.type === "home") &&
      (data.type === "back" || data.type === "home" ||
      [data.x, data.y, ...(data.type === "swipe" ? [data.toX, data.toY] : [])]
        .every(n => typeof n === "number" && Number.isFinite(n) && n >= 0 && n <= 1))) {
    if (data.type === "swipe") data.ms = Math.max(100, Math.min(1200, Number(data.ms) || 300));
    message(other, data);
  }
}
function consume(peer, chunk) {
  peer.buffer = Buffer.concat([peer.buffer, chunk]);
  if (peer.buffer.length > 500014) { close(peer, 1009); return; }
  while (peer.buffer.length >= 2) {
    const b = peer.buffer;
    const fin = (b[0] & 0x80) !== 0, opcode = b[0] & 15;
    const masked = (b[1] & 0x80) !== 0;
    let len = b[1] & 127, offset = 2;
    if (!fin || !masked) { close(peer, 1002); return; }
    if (len === 126) { if (b.length < 4) return; len = b.readUInt16BE(2); offset = 4; }
    if (len === 127) {
      if (b.length < 10) return;
      const big = b.readBigUInt64BE(2);
      if (big > 500000n) { close(peer, 1009); return; }
      len = Number(big); offset = 10;
    }
    if (len > 500000) { close(peer, 1009); return; }
    if (b.length < offset + 4 + len) return;
    const mask = b.subarray(offset, offset + 4);
    const payload = Buffer.from(b.subarray(offset + 4, offset + 4 + len));
    for (let i = 0; i < len; i++) payload[i] ^= mask[i % 4];
    peer.buffer = b.subarray(offset + 4 + len);
    packet(peer, opcode, payload);
    if (peer.socket.destroyed) return;
  }
}
server.on("upgrade", (req, socket, head) => {
  const key = req.headers["sec-websocket-key"];
  if (req.url !== "/ws" || req.headers.upgrade?.toLowerCase() !== "websocket" ||
      req.headers["sec-websocket-version"] !== "13" || typeof key !== "string" ||
      Buffer.from(key, "base64").length !== 16) { socket.destroy(); return; }
  // A browser dashboard must be loaded from this same host.
  if (req.headers.origin) {
    try { if (new URL(req.headers.origin).host !== req.headers.host) { socket.destroy(); return; } }
    catch { socket.destroy(); return; }
  }
  const accept = crypto.createHash("sha1").update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
  socket.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
  const peer = { socket, role: null, done: false, buffer: Buffer.alloc(0) };
  const timeout = setTimeout(() => { if (!peer.role) close(peer, 1008); }, 5000);
  timeout.unref();
  socket.on("data", data => consume(peer, data));
  socket.on("error", () => disconnect(peer));
  socket.on("close", () => { clearTimeout(timeout); disconnect(peer); });
  if (head.length) consume(peer, head);
});
server.listen(PORT, HOST, () => console.log(`Relay listening at http://${HOST}:${server.address().port}`));
