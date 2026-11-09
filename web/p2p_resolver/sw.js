// Service Worker: every request under the page's folder, except the resolver's
// own files, is handed to the resolver page that owns the WebRTC connection to
// the server, and the streamed answer is turned back into a Response. Which
// sub-folder the remote app lives in is up to the server (it announces it),
// so this works for any HTTP app, not just one.
'use strict'

const SCOPE = new URL(self.registration.scope).pathname // e.g. /p2p_resolver/
// The resolver's own files; everything else in scope belongs to the server.
// Keep in sync with OWN_FILES in web/src/main.js.
const OWN = new Set(['', 'index.html', 'sw.js', 'resolver.js', 'manifest.json', 'icon.svg', 'icon-192.png', 'icon-512.png'].map(f => SCOPE + f))
const BODY_WINDOW = 4 << 20 // request body bytes in flight to the page
const HWM = 1 << 20 // bytes buffered in the SW before backpressure kicks in
const NULL_BODY = new Set([101, 103, 204, 205, 304])

/** hostId -> { clientId, ready } ; clientId -> hostId */
const hosts = new Map()
const clientHost = new Map()

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()))

self.addEventListener('message', (e) => {
  const m = e.data
  if (m && m.type === 'host' && e.source) {
    hosts.set(m.hostId, { clientId: e.source.id, ready: !!m.ready })
  }
})

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url)
  if (url.origin !== self.location.origin || !url.pathname.startsWith(SCOPE) || OWN.has(url.pathname)) return
  e.respondWith(tunnel(e, url))
})

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function findHost (hostId) {
  // Up to ~15 s: covers a reconnect in progress and SW restarts (which wipe
  // our maps; the pages re-announce themselves when probed).
  for (let i = 0; i < 30; i++) {
    const order = []
    if (hostId && hosts.has(hostId)) order.push(hosts.get(hostId))
    for (const [id, h] of hosts) if (id !== hostId) order.push(h)
    for (const h of order) {
      if (!h.ready) continue
      const c = await self.clients.get(h.clientId)
      if (c) return c
    }
    if (i % 4 === 0) {
      const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      for (const w of wins) w.postMessage({ type: 'probe' })
    }
    // Nobody is even trying to connect: give up quickly.
    if (i >= 2 && hosts.size === 0) return null
    await sleep(500)
  }
  return null
}

function offline (request) {
  const msg = 'This tab is not connected to the server. Open the server link again (…?server_name=…).'
  if (request.mode === 'navigate') {
    const html = `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Not connected</title><body style="font:16px system-ui;padding:2rem;max-width:36rem;margin:auto">
<h2>Not connected</h2><p>${msg}</p><p><a href="${SCOPE}">Go to the resolver page</a></p></body>`
    return new Response(html, { status: 503, headers: { 'Content-Type': 'text/html; charset=utf-8' } })
  }
  return new Response(msg, { status: 503, headers: { 'Content-Type': 'text/plain; charset=utf-8' } })
}

async function tunnel (e, url) {
  let hostId = url.searchParams.get('__p2p_host')
  if (hostId) {
    url.searchParams.delete('__p2p_host')
    if (e.resultingClientId) clientHost.set(e.resultingClientId, hostId)
  } else {
    hostId = clientHost.get(e.clientId) || clientHost.get(e.resultingClientId)
    if (hostId && e.resultingClientId) clientHost.set(e.resultingClientId, hostId)
  }

  const request = e.request
  const client = await findHost(hostId)
  if (!client) return offline(request)

  const hasBody = request.method !== 'GET' && request.method !== 'HEAD'
  // Stream request bodies where the browser exposes them as a stream
  // (Chromium); elsewhere (Firefox, Safari) fall back to buffering. Apps
  // that upload in chunks (e.g. FileBrowser, 10 MB per request) are fine
  // either way; streaming keeps memory flat for big single-request uploads.
  const stream = hasBody && request.body instanceof ReadableStream ? request.body : null
  const body = hasBody && !stream ? await request.arrayBuffer() : null
  const headers = []
  request.headers.forEach((v, k) => headers.push([k, v]))
  const { port1, port2 } = new MessageChannel()
  const transfer = [port2]
  if (body && body.byteLength) transfer.push(body)
  client.postMessage({
    type: 'fetch',
    req: { method: request.method, url: url.pathname + url.search, headers, body: body && body.byteLength ? body : null, stream: !!stream }
  }, transfer)

  // Upload flow control: the page acks body bytes once they're handed to the
  // DataChannel, so at most BODY_WINDOW bytes sit in the page's memory.
  let bodyAcked = 0
  let bodyWake = null
  let aborted = false
  if (stream) {
    (async () => {
      const reader = stream.getReader()
      let posted = 0
      try {
        for (;;) {
          while (posted - bodyAcked >= BODY_WINDOW && !aborted) await new Promise(resolve => { bodyWake = resolve })
          if (aborted) { reader.cancel().catch(() => {}); return }
          const { value, done } = await reader.read()
          if (done) break
          if (!value || !value.byteLength) continue
          port1.postMessage({ type: 'body', data: value })
          posted += value.byteLength
        }
        port1.postMessage({ type: 'body-end' })
      } catch (err) {
        port1.postMessage({ type: 'body-error', message: String(err) })
      }
    })()
  }

  return new Promise((resolve) => {
    let controller = null
    let enqueued = 0
    let settled = false
    const ack = () => {
      if (!controller) return
      const queued = HWM - controller.desiredSize
      port1.postMessage({ type: 'ack', n: enqueued - queued })
    }
    const settle = (r) => { if (!settled) { settled = true; resolve(r) } }

    const stopBody = () => { aborted = true; bodyWake?.() }
    request.signal?.addEventListener('abort', () => { stopBody(); port1.postMessage({ type: 'cancel' }) })

    port1.onmessage = (ev) => {
      const m = ev.data
      if (m.type === 'body-ack') {
        bodyAcked = m.n
        bodyWake?.()
      } else if (m.type === 'head') {
        const status = m.status >= 200 && m.status <= 599 ? m.status : 502
        const h = new Headers()
        for (const [k, v] of m.headers) { try { h.append(k, v) } catch (_) {} }
        if (NULL_BODY.has(status) || request.method === 'HEAD') {
          settle(new Response(null, { status, headers: h }))
          return
        }
        const stream = new ReadableStream({
          start (c) { controller = c },
          pull () { ack() },
          cancel () { port1.postMessage({ type: 'cancel' }) }
        }, new ByteLengthQueuingStrategy({ highWaterMark: HWM }))
        settle(new Response(stream, { status, headers: h }))
      } else if (m.type === 'chunk') {
        if (!controller) return
        try {
          controller.enqueue(new Uint8Array(m.data))
          enqueued += m.data.byteLength
          ack()
        } catch (_) { port1.postMessage({ type: 'cancel' }) }
      } else if (m.type === 'end') {
        stopBody()
        try { controller && controller.close() } catch (_) {}
        port1.close()
      } else if (m.type === 'error') {
        stopBody()
        if (controller) { try { controller.error(new Error(m.message)) } catch (_) {} } else {
          settle(new Response('Server connection error: ' + m.message, { status: 502, headers: { 'Content-Type': 'text/plain; charset=utf-8' } }))
        }
        port1.close()
      }
    }
  })
}
