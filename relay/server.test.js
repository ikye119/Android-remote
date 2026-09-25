"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const { once } = require("node:events");

const phoneToken = "p".repeat(64), viewerToken = "v".repeat(64);
function event(socket, expected) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Timed out waiting for ${expected}`)), 2000);
    function onMessage(e) {
      const obj = typeof e.data === "string" ? JSON.parse(e.data) : e.data;
      if (obj.type !== expected) return;
      clearTimeout(timer); socket.removeEventListener("message", onMessage); resolve(obj);
    }
    socket.addEventListener("message", onMessage);
  });
}
test("tokens isolate roles and relay frames, controls and disconnect", async () => {
  const processRelay = spawn(process.execPath, ["server.js"], {
    cwd: __dirname, env: { ...process.env, PHONE_TOKEN: phoneToken, VIEWER_TOKEN: viewerToken, RELAY_HOST: "127.0.0.1", RELAY_PORT: "0" },
    stdio: ["ignore", "pipe", "pipe"]
  });
  const opened = new Promise((resolve, reject) => {
    processRelay.stdout.on("data", chunk => {
      const match = chunk.toString().match(/:(\d+)/); if (match) resolve(Number(match[1]));
    });
    processRelay.on("exit", code => reject(new Error(`Relay exited ${code}`)));
  });
  let phone, viewer, bad;
  try {
    const port = await opened;
    const page = await fetch(`http://127.0.0.1:${port}/`);
    assert.equal(page.status, 200);
    assert.match(await page.text(), /Start screen sharing|Your phone screen/);
    bad = new WebSocket(`ws://127.0.0.1:${port}/ws`);
    await once(bad, "open");
    bad.send(JSON.stringify({ role: "phone", token: viewerToken }));
    const badClose = await once(bad, "close");
    assert.equal(badClose[0].code, 1008);
    viewer = new WebSocket(`ws://127.0.0.1:${port}/ws`);
    await once(viewer, "open");
    const readyViewer = event(viewer, "ready");
    viewer.send(JSON.stringify({ role: "viewer", token: viewerToken }));
    assert.equal((await readyViewer).peerConnected, false);
    phone = new WebSocket(`ws://127.0.0.1:${port}/ws`);
    await once(phone, "open");
    const joined = event(viewer, "peer"), readyPhone = event(phone, "ready");
    phone.send(JSON.stringify({ role: "phone", token: phoneToken }));
    assert.equal((await joined).connected, true);
    assert.equal((await readyPhone).peerConnected, true);
    const screen = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Frame missing")), 2000);
      viewer.addEventListener("message", e => { if (e.data instanceof Blob) { clearTimeout(timer); resolve(e.data); } }, { once: false });
    });
    phone.send(new Uint8Array([255,216,255,217]));
    assert.deepEqual([...new Uint8Array(await (await screen).arrayBuffer())], [255,216,255,217]);
    const control = event(phone, "tap");
    viewer.send(JSON.stringify({ type: "tap", x: 0.25, y: 0.5 }));
    assert.equal((await control).x, 0.25);
    const left = event(phone, "peer"); viewer.close();
    assert.equal((await left).connected, false);
  } finally {
    phone?.close(); viewer?.close(); bad?.close(); processRelay.kill();
  }
});
