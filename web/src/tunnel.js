// Server connection: libp2p is used only to reach the server (direct or via a
// public relay) and run a tiny authenticated signaling exchange. HTTP traffic
// then flows over a plain WebRTC peer connection, one DataChannel per request.
// Wire formats are documented in go/signal.go and go/bridge.go.
import { createLibp2p } from 'libp2p'
import { noise } from '@chainsafe/libp2p-noise'
import { yamux } from '@chainsafe/libp2p-yamux'
import { identify } from '@libp2p/identify'
import { webRTCDirect } from '@libp2p/webrtc'
import { webTransport } from '@libp2p/webtransport'
import { webSockets } from '@libp2p/websockets'
import { circuitRelayTransport } from '@libp2p/circuit-relay-v2'
import { lpStream } from '@libp2p/utils'
import { multiaddr } from '@multiformats/multiaddr'

export const SIGNAL_PROTOCOL = '/p2p-http/signal/1.0.0'
// Wire-format version, exchanged in hello/config. Must match
// ProtocolVersion in go/signal.go; MIN_SERVER_VERSION is the oldest server
// this page still works with.
export const PROTOCOL_VERSION = 1
const MIN_SERVER_VERSION = 1
const CHUNK = 16 * 1024
const ACK_EVERY = 256 * 1024
const ADDR_TIMEOUT = 5000

const enc = new TextEncoder()
const dec = new TextDecoder()

function withTimeout (promise, ms, what) {
  let t
  return Promise.race([
    promise.finally(() => clearTimeout(t)),
    new Promise((_resolve, reject) => { t = setTimeout(() => reject(new Error(`${what} timed out`)), ms) })
  ])
}

export async function startLibp2p () {
  return createLibp2p({
    addresses: { listen: [] },
    transports: [webRTCDirect(), webTransport(), webSockets(), circuitRelayTransport()],
    connectionEncrypters: [noise()],
    streamMuxers: [yamux()],
    services: { identify: identify() },
    // Per-address dial timeout (see dialServer for the overall budgets).
    connectionManager: { addressDialTimeout: ADDR_TIMEOUT },
    // Allow LAN/private addresses so a browser on the same Wi-Fi can connect directly.
    connectionGater: { denyDialMultiaddr: () => false }
  })
}

const isRelayed = (a) => a.includes('/p2p-circuit')

// AbortSignal.any() is too new for our browser targets.
function withDeadline (parent, ms) {
  const ctl = new AbortController()
  const t = setTimeout(() => ctl.abort(new Error(`timed out after ${ms / 1000}s`)), ms)
  const stop = () => { clearTimeout(t); ctl.abort(parent.reason) }
  if (parent.aborted) stop(); else parent.addEventListener('abort', stop, { once: true })
  return { signal: ctl.signal, clear: () => { clearTimeout(t); parent.removeEventListener('abort', stop) } }
}

// One sequential js-libp2p dial over `addrs` with an overall budget.
// Returns the connection, or null (failure reason pushed to `errors`).
async function dialPhase (node, addrs, budget, what, { signal, log, errors = [] }) {
  if (addrs.length === 0) return null
  log(`Trying ${addrs.length} ${what} address${addrs.length > 1 ? 'es' : ''}…`)
  const deadline = withDeadline(signal, budget)
  try {
    return await node.dial(addrs.map(a => multiaddr(a)), { signal: deadline.signal })
  } catch (err) {
    if (signal.aborted) throw err
    errors.push(`${what}: ${err.message}`)
    console.warn(`[p2p] ${what} dial failed`, err)
    return null
  } finally {
    deadline.clear()
  }
}

const withPeer = (a, peerId) => a.includes(`/p2p/${peerId}`) ? a : `${a}/p2p/${peerId}`

/**
 * Dial addresses remembered from a previous visit (the server's LAN
 * addresses). On the same network this connects in well under a second;
 * elsewhere it fails fast, and the caller runs the DHT lookup in parallel so
 * nothing is lost. Returns { conn, viaRelay } or null.
 */
export async function dialRemembered (node, peerId, addrs, { signal, log }) {
  if (addrs.length === 0) return null
  log('Trying the local network…')
  // One at a time with a short timeout each, so an address from another
  // network (e.g. the phone's mobile data IP) can't eat the others' time.
  for (const a of addrs) {
    const deadline = withDeadline(signal, 2000)
    try {
      const conn = await node.dial(multiaddr(withPeer(a, peerId)), { signal: deadline.signal })
      log('Reached server directly on the local network.')
      return { conn, viaRelay: false }
    } catch (err) {
      if (signal.aborted) throw err
    } finally {
      deadline.clear()
    }
  }
  log('Not on the same network.')
  return null
}

/**
 * Dial the server. js-libp2p dials a peer's addresses strictly one after the
 * other (up to ADDR_TIMEOUT each, relay addresses last), so a server that
 * advertises many unreachable direct addresses (LAN IPs, firewalled IPv6)
 * used to burn the whole budget before the relay was ever tried. Instead:
 *   1. direct addresses, with a short overall budget (they either answer
 *      within a second or two, or never),
 *   2. relayed addresses, with their own full budget.
 */
export async function dialServer (node, peerId, addrStrings, { signal, log, relayOnly = false }) {
  const usable = [...new Set(addrStrings)]
    .filter(a => /\/(webrtc-direct|webtransport|wss|tls\/ws|ws)(\/|$)/.test(a))
    .filter(a => !a.includes('/ip4/127.') && !a.includes('/ip6/::1/'))
    .map(a => withPeer(a, peerId))
  const direct = relayOnly ? [] : usable.filter(a => !isRelayed(a))
  const relayed = usable.filter(isRelayed)
  if (direct.length + relayed.length === 0) throw new Error('The server has no address a browser can use yet (no relay?).')

  const errors = []
  const conn = await dialPhase(node, direct, Math.min(15000, 3000 + 2000 * direct.length), 'direct', { signal, log, errors }) ??
               await dialPhase(node, relayed, 30000, 'relay', { signal, log, errors })
  if (!conn) throw new Error(`Could not reach the server (${errors.join('; ')}).`)
  const viaRelay = isRelayed(conn.remoteAddr.toString())
  log(`Reached server via ${viaRelay ? 'relay' : 'direct link'}.`)
  return { conn, viaRelay }
}

/**
 * Run signaling over the libp2p connection and return a connected
 * RTCPeerConnection plus the server's config.
 */
export async function negotiate (conn, { clientId, base, log, signal, viaRelay = false }) {
  let stream
  try {
    stream = await conn.newStream(SIGNAL_PROTOCOL, { runOnLimitedConnection: true, signal })
  } catch (err) {
    if (/unsupported ?protocol|protocol selection failed|protocols? not supported/i.test(`${err.name} ${err.message}`)) {
      throw Object.assign(new Error('The server runs an incompatible version of the P2P software (it does not speak ' +
        SIGNAL_PROTOCOL + '). Update the server app, or open it with the resolver page that matches its version.'), { fatal: true })
    }
    throw err
  }
  const lp = lpStream(stream)
  const send = (m) => lp.write(enc.encode(JSON.stringify(m)), { signal })
  const recv = async () => {
    const buf = await lp.read({ signal })
    const m = JSON.parse(dec.decode(buf.subarray()))
    if (m.type === 'error') throw new Error(`Server: ${m.error}`)
    return m
  }

  await send({ type: 'hello', v: PROTOCOL_VERSION, client: clientId, base })
  let cfg
  try {
    cfg = await recv()
  } catch (err) {
    // The server's refusal of an outdated page is final; don't auto-retry.
    if (/outdated/i.test(err.message)) err.fatal = true
    throw err
  }
  if (cfg.type !== 'config') throw new Error('unexpected signaling reply')
  if ((cfg.v ?? 0) < MIN_SERVER_VERSION) {
    throw Object.assign(new Error(`The server app is outdated (protocol v${cfg.v ?? 0}, this page needs at least v${MIN_SERVER_VERSION}). Update the app on the server.`), { fatal: true })
  }

  const pc = new RTCPeerConnection({ iceServers: cfg.iceServers ?? [] })
  pc.createDataChannel('ctrl')
  const gathered = new Promise(resolve => {
    if (pc.iceGatheringState === 'complete') return resolve()
    pc.addEventListener('icegatheringstatechange', () => { if (pc.iceGatheringState === 'complete') resolve() })
  })
  await pc.setLocalDescription(await pc.createOffer())
  // Non-trickle ICE keeps the protocol trivial; cap the wait for slow STUN.
  await Promise.race([gathered, new Promise(resolve => setTimeout(resolve, 5000))])
  log('Negotiating direct WebRTC link…')
  await send({ type: 'offer', sdp: pc.localDescription.sdp })
  const ans = await recv()
  if (ans.type !== 'answer') throw new Error('unexpected signaling reply')
  await pc.setRemoteDescription({ type: 'answer', sdp: ans.sdp })
  try { await stream.close() } catch {}

  await withTimeout(new Promise((resolve, reject) => {
    const check = () => {
      if (pc.connectionState === 'connected') resolve()
      else if (pc.connectionState === 'failed') {
        reject(new Error(viaRelay
          ? 'The server was reached through a relay, but a direct WebRTC link could not be punched through the NATs on both sides. If this keeps happening, add a TURN server on the server.'
          : 'WebRTC could not punch through the NATs (a TURN server may be needed).'))
      }
    }
    pc.addEventListener('connectionstatechange', check)
    check()
  }), 30000, 'WebRTC connection')
  return { pc, config: cfg }
}

/**
 * Proxies one HTTP request (from the Service Worker, via `port`) over a new
 * DataChannel. Messages on port: from SW {type:'ack',n} | {type:'cancel'};
 * to SW {type:'head'|'chunk'|'end'|'error'}.
 */
export function proxyRequest (pc, req, port) {
  let dc
  try {
    dc = pc.createDataChannel('http', { ordered: true })
  } catch (err) {
    port.postMessage({ type: 'error', message: 'connection lost: ' + err.message })
    return
  }
  dc.binaryType = 'arraybuffer'
  dc.bufferedAmountLowThreshold = 256 * 1024
  let gotHead = false
  let done = false
  let lastAck = 0

  // Request body: either a whole buffer (req.body) or streamed in from the
  // Service Worker (req.stream) as 'body' messages, acked as they are sent.
  const queue = []
  let bodyEnd = false
  let headSent = false
  let pumping = false
  let bodySent = 0

  const finish = (msg) => {
    if (done) return
    done = true
    if (msg) port.postMessage(msg)
    try { dc.close() } catch {}
    port.close()
  }

  const drained = () => new Promise(resolve => {
    if (dc.bufferedAmount <= 1024 * 1024 || dc.readyState !== 'open') return resolve()
    dc.onbufferedamountlow = () => { dc.onbufferedamountlow = null; resolve() }
  })

  const sendBytes = async (bytes) => {
    for (let off = 0; off < bytes.byteLength && !done; off += CHUNK) {
      await drained()
      if (dc.readyState !== 'open') return false
      dc.send(bytes.subarray(off, off + CHUNK))
    }
    return dc.readyState === 'open'
  }

  const pump = async () => {
    if (pumping || !headSent || done) return
    pumping = true
    try {
      while (queue.length && !done) {
        const chunk = queue.shift()
        if (!await sendBytes(chunk)) return
        bodySent += chunk.byteLength
        port.postMessage({ type: 'body-ack', n: bodySent })
      }
      if (bodyEnd === true && !done && dc.readyState === 'open') {
        bodyEnd = 'sent'
        dc.send('end')
      }
    } finally {
      pumping = false
    }
  }

  port.onmessage = (ev) => {
    const m = ev.data
    if (m.type === 'ack' && dc.readyState === 'open' && m.n - lastAck >= ACK_EVERY) {
      lastAck = m.n
      dc.send('ack:' + m.n)
    } else if (m.type === 'cancel') {
      finish()
    } else if (m.type === 'body') {
      queue.push(m.data instanceof Uint8Array ? m.data : new Uint8Array(m.data))
      pump()
    } else if (m.type === 'body-end') {
      bodyEnd = true
      pump()
    } else if (m.type === 'body-error') {
      finish({ type: 'error', message: 'upload aborted: ' + m.message })
    }
  }

  dc.onopen = async () => {
    const body = req.body ? new Uint8Array(req.body) : null
    dc.send(JSON.stringify({ method: req.method, url: req.url, headers: req.headers, body: !!body || !!req.stream }))
    if (req.stream) {
      headSent = true
      pump()
    } else if (body) {
      if (await sendBytes(body)) dc.send('end')
    }
  }
  dc.onmessage = (ev) => {
    if (typeof ev.data === 'string') {
      if (!gotHead) {
        gotHead = true
        const h = JSON.parse(ev.data)
        port.postMessage({ type: 'head', status: h.status, headers: h.headers ?? [] })
      } else if (ev.data === 'end') {
        finish({ type: 'end' })
      } else if (ev.data.startsWith('error:')) {
        finish({ type: 'error', message: ev.data.slice(6) })
      }
      return
    }
    port.postMessage({ type: 'chunk', data: ev.data }, [ev.data])
  }
  dc.onclose = () => finish({ type: 'error', message: 'channel closed before the response finished' })
  dc.onerror = () => finish({ type: 'error', message: 'channel error' })
}
