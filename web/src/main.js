import { peerIdFromString } from '@libp2p/peer-id'
import { findAddrs, DEFAULT_ROUTERS } from './discovery.js'
import { startLibp2p, dialRemembered, dialServer, negotiate, proxyRequest } from './tunnel.js'

// Generic resolver: the page lives at <dir>/ (e.g. /p2p_resolver/) and proxies
// whatever HTTP app the server exposes. The server announces the path its app
// is served under (config.base); it must be a sub-folder of <dir> so the
// Service Worker (scoped to <dir>) can route it. Nothing here is app-specific.
const DIR = location.pathname.replace(/[^/]*$/, '')
let appBase = null // set from the server's config on first connect
// Keep in sync with OWN in p2p_resolver/sw.js.
const OWN_FILES = new Set(['index.html', 'sw.js', 'resolver.js', 'manifest.json', 'icon.svg', 'icon-192.png', 'icon-512.png'])
const params = new URLSearchParams(location.search)
const serverName = (params.get('server_name') || '').trim()
const extraAddrs = params.getAll('addr')
// ?via=relay forces the relayed path (debugging / testing NAT traversal).
const relayOnly = params.get('via') === 'relay'
// ?router=https://… (repeatable) adds delegated-routing endpoints to try
// after the default one, e.g. a self-hosted someguy instance.
const routers = [...DEFAULT_ROUTERS, ...params.getAll('router').filter(r => /^https:\/\/[^/]/.test(r)).map(r => r.replace(/\/+$/, ''))]
const hostId = crypto.randomUUID()

const $ = (id) => document.getElementById(id)
const ui = {
  overlay: $('overlay'),
  card: $('card'),
  title: $('title'),
  steps: $('steps'),
  server: $('server'),
  error: $('error'),
  retry: $('retry'),
  form: $('pick'),
  frame: $('frame'),
  badge: $('badge'),
  saved: $('saved'),
  servers: $('servers'),
  addLabel: $('add-label'),
  home: $('home'),
  remember: $('remember'),
  device: $('device'),
  deviceRemember: $('device-remember'),
  forget: $('forget')
}

let pc = null
let connecting = false
let retryTimer = null
let attempt = 0

// --- What this browser keeps -------------------------------------------
// By default nothing outlives the tab: the client id (the key to this
// visitor's login cookies, which the server holds), the saved servers and
// the remembered addresses all live in sessionStorage. Only when the visitor
// ticks "Remember me on this device" do they move to localStorage. So on a
// shared or public computer, closing the tab is enough to log out and leave
// no trace of the server.

const REMEMBER_KEY = 'p2p-remember'
const CLIENT_KEY = 'p2p-client-id'
const ourKey = (k) => k === CLIENT_KEY || k === SERVERS_KEY || k.startsWith('p2p-local-addrs:') || k.startsWith('p2p-relay-addrs:')

function isRemembered () {
  try { return localStorage.getItem(REMEMBER_KEY) === '1' } catch { return false }
}

/** Storage for our keys, or null if the browser blocks storage. */
function store () {
  try { return isRemembered() ? localStorage : sessionStorage } catch { return null }
}

function eachOurKey (s, f) {
  try { for (const k of Object.keys(s)) if (ourKey(k)) f(k) } catch {}
}

// Earlier versions kept everything in localStorage unconditionally. Data
// there without the remember flag was never opted in: drop it.
function dropUnrememberedData () {
  try { if (!isRemembered()) eachOurKey(localStorage, k => localStorage.removeItem(k)) } catch {}
}

/** Move our data between tab-only and device storage, keeping this visit's login. */
function setRemember (on) {
  if (on === isRemembered()) return
  try {
    const [from, to] = on ? [sessionStorage, localStorage] : [localStorage, sessionStorage]
    eachOurKey(from, k => { to.setItem(k, from.getItem(k)); from.removeItem(k) })
    if (on) localStorage.setItem(REMEMBER_KEY, '1')
    else localStorage.removeItem(REMEMBER_KEY)
  } catch {}
  renderRemember()
}

/** Wipe everything this page stored in this browser and start over. */
function forgetDevice () {
  try {
    for (const s of [localStorage, sessionStorage]) {
      eachOurKey(s, k => s.removeItem(k))
      s.removeItem(REMEMBER_KEY)
    }
  } catch {}
  memClientId = null
  if (pc) { const p = pc; pc = null; p.close() }
  location.replace(DIR)
}

function renderRemember () {
  const on = isRemembered()
  ui.remember.checked = on
  ui.deviceRemember.checked = on
}

let memClientId = null // fallback when storage is blocked

function clientId () {
  const fresh = () => [...crypto.getRandomValues(new Uint8Array(16))].map(b => b.toString(16).padStart(2, '0')).join('')
  const s = store()
  try {
    let id = s?.getItem(CLIENT_KEY)
    if (!id) { id = memClientId || fresh(); s?.setItem(CLIENT_KEY, id) }
    return (memClientId = id)
  } catch { return (memClientId ||= fresh()) }
}

// --- Saved servers ---------------------------------------------------------
// Every server this browser connected to successfully is saved (PeerID +
// the name it reported), so later visits can just pick it from a list. The
// PeerID *is* the server's public key, and the connection is authenticated
// against it, so a saved entry always reaches the same server: the name is
// only a label. Stored in this browser only, and only for this tab unless
// the visitor chose "Remember me on this device".

const SERVERS_KEY = 'p2p-servers'

function savedServers () {
  try {
    const list = JSON.parse(store()?.getItem(SERVERS_KEY) || '[]')
    return Array.isArray(list) ? list.filter(s => s && typeof s.id === 'string') : []
  } catch { return [] }
}

function writeServers (list) {
  try { store()?.setItem(SERVERS_KEY, JSON.stringify(list)) } catch {}
}

function saveServer (id, name) {
  const list = savedServers()
  const old = list.find(s => s.id === id)
  const entry = { id, name: (name || old?.name || '').slice(0, 80), lastSeen: Date.now() }
  writeServers([entry, ...list.filter(s => s.id !== id)])
}

function forgetServer (id) {
  writeServers(savedServers().filter(s => s.id !== id))
  try { store()?.removeItem(ADDR_KEY(id)); store()?.removeItem(RELAY_KEY(id)) } catch {}
}

function ago (t) {
  const s = Math.max(0, (Date.now() - t) / 1000)
  if (s < 90) return 'just now'
  if (s < 5400) return `${Math.round(s / 60)} min ago`
  if (s < 129600) return `${Math.round(s / 3600)} h ago`
  return `${Math.round(s / 86400)} days ago`
}

function renderServers () {
  const list = savedServers()
  ui.servers.replaceChildren(...list.map(srv => {
    const li = document.createElement('li')
    const a = document.createElement('a')
    a.href = '?server_name=' + encodeURIComponent(srv.id)
    const name = document.createElement('strong')
    name.textContent = srv.name || 'Unnamed server'
    const meta = document.createElement('small')
    const code = document.createElement('code')
    code.textContent = srv.id.slice(0, 10) + '…' + srv.id.slice(-6)
    code.title = srv.id
    meta.append(code, ` · ${srv.lastSeen ? ago(srv.lastSeen) : ''}`)
    a.append(name, meta)
    const del = document.createElement('button')
    del.type = 'button'
    del.textContent = 'Forget'
    let armed = null
    del.addEventListener('click', () => {
      if (!armed) {
        del.textContent = 'Forget?'
        del.classList.add('confirm')
        armed = setTimeout(() => { armed = null; del.textContent = 'Forget'; del.classList.remove('confirm') }, 3000)
        return
      }
      clearTimeout(armed)
      forgetServer(srv.id)
      renderPicker()
    })
    li.append(a, del)
    return li
  }))
  return list.length
}

function renderPicker () {
  const n = renderServers()
  ui.card.classList.add('picker')
  ui.title.textContent = n ? 'Open a server' : 'Open a P2P server'
  ui.saved.hidden = n === 0
  ui.addLabel.hidden = n === 0
  ui.form.hidden = false
  ui.overlay.hidden = false
  ui.badge.hidden = true
}

// --- Remembered local addresses -----------------------------------------
// The server tells us its direct addresses during signaling; the public DHT
// never returns private (LAN) ones. Remember those per server so the next
// visit from the same network connects straight away. Stored only in this
// browser (see store()), never sent anywhere.

const ADDR_KEY = (id) => 'p2p-local-addrs:' + id
const ADDR_TTL = 30 * 24 * 3600 * 1000
const MAX_REMEMBERED = 3

// Rank of a private address: typical home LAN ranges first, carrier-grade NAT
// (mobile data, Tailscale) last; -1 = not a private address.
function localRank (a) {
  const v4 = a.match(/^\/ip4\/(\d+)\.(\d+)\./)
  if (v4) {
    const [x, y] = [+v4[1], +v4[2]]
    if (x === 192 && y === 168) return 0
    if (x === 172 && y >= 16 && y <= 31) return 1
    if (x === 10) return 2
    if (x === 100 && y >= 64 && y <= 127) return 4
    return -1
  }
  return /^\/ip6\/f[cd]/i.test(a) ? 3 : -1
}

function rememberedAddrs (id) {
  try {
    const e = JSON.parse(store()?.getItem(ADDR_KEY(id)) || 'null')
    if (!e || Date.now() - e.t > ADDR_TTL || !Array.isArray(e.addrs)) return []
    return e.addrs.filter(a => typeof a === 'string')
  } catch { return [] }
}

function rememberAddrs (id, addrs) {
  // WebRTC-direct only: every browser supports it and its certificate hash is
  // stable for a given server key (WebTransport's rotates).
  const local = (addrs || [])
    .filter(a => typeof a === 'string' && a.includes('/webrtc-direct/') && !a.includes('/p2p-circuit') && localRank(a) >= 0)
    .sort((a, b) => localRank(a) - localRank(b))
    .slice(0, MAX_REMEMBERED)
  try {
    if (local.length) store()?.setItem(ADDR_KEY(id), JSON.stringify({ t: Date.now(), addrs: local }))
    else store()?.removeItem(ADDR_KEY(id))
  } catch {}
}

// Relay routes change when the server re-picks relays, so they are kept for
// a day only; stale ones just fail fast within the relay phase.
const RELAY_KEY = (id) => 'p2p-relay-addrs:' + id
const RELAY_TTL = 24 * 3600 * 1000
const MAX_RELAYS = 8

function rememberedRelays (id) {
  try {
    const e = JSON.parse(store()?.getItem(RELAY_KEY(id)) || 'null')
    if (!e || Date.now() - e.t > RELAY_TTL || !Array.isArray(e.addrs)) return []
    return e.addrs.filter(a => typeof a === 'string' && a.includes('/p2p-circuit'))
  } catch { return [] }
}

function rememberRelays (id, addrs) {
  // Secure WebSockets first (no rotating certificate hash), WebTransport last.
  const rank = (a) => /\/(wss|tls\/ws|tls\/sni)/.test(a) ? 0 : a.includes('/webrtc-direct') ? 1 : 2
  const relays = (addrs || []).filter(a => typeof a === 'string' && a.includes('/p2p-circuit'))
    .sort((a, b) => rank(a) - rank(b)).slice(0, MAX_RELAYS)
  try {
    if (relays.length) store()?.setItem(RELAY_KEY(id), JSON.stringify({ t: Date.now(), addrs: relays }))
  } catch {}
}

function log (msg) {
  const li = document.createElement('li')
  li.textContent = msg
  ui.steps.append(li)
  while (ui.steps.children.length > 6) ui.steps.firstChild.remove()
  console.info('[p2p]', msg)
}

function showOverlay (title) {
  ui.title.textContent = title
  ui.overlay.hidden = false
  ui.error.hidden = true
  ui.retry.hidden = true
}

function showError (msg, { retryIn } = {}) {
  ui.error.textContent = msg
  ui.error.hidden = false
  ui.retry.hidden = false
  ui.title.textContent = 'Could not connect'
  if (retryIn) {
    clearTimeout(retryTimer)
    retryTimer = setTimeout(connect, retryIn)
    ui.retry.textContent = `Retry now (auto in ${Math.round(retryIn / 1000)}s)`
  } else {
    ui.retry.textContent = 'Retry'
  }
}

function setBadge (state) {
  ui.badge.dataset.state = state
  ui.badge.textContent = state === 'ok' ? 'Connected peer-to-peer' : state === 'warn' ? 'Reconnecting…' : 'Offline'
}

// --- Service Worker plumbing ---------------------------------------------

async function setupServiceWorker () {
  if (!('serviceWorker' in navigator)) throw new Error('This browser has no Service Worker support (private mode in Firefox disables it).')
  const reg = await navigator.serviceWorker.register('sw.js', { scope: './' })
  navigator.serviceWorker.addEventListener('message', (ev) => {
    const m = ev.data
    if (m?.type === 'probe') announce()
    else if (m?.type === 'fetch') {
      const port = ev.ports[0]
      if (!pc || pc.connectionState !== 'connected') {
        port.postMessage({ type: 'error', message: 'not connected to the server (reconnecting)' })
        port.close()
      } else {
        proxyRequest(pc, m.req, port)
      }
    }
  })
  navigator.serviceWorker.startMessages()
  await navigator.serviceWorker.ready
  return reg
}

function announce () {
  const ready = !!pc && pc.connectionState === 'connected'
  const sw = navigator.serviceWorker.controller
  navigator.serviceWorker.ready.then(reg => {
    (reg.active || sw)?.postMessage({ type: 'host', hostId, ready })
  })
}

// --- Connection ----------------------------------------------------------

async function connect () {
  if (connecting) return
  connecting = true
  clearTimeout(retryTimer)
  attempt++
  showOverlay(pc ? 'Reconnecting to the server…' : 'Connecting to the server…')
  const abort = new AbortController()
  let node
  try {
    node = await startLibp2p()
    const dialSignal = AbortSignal.timeout(60000)
    // Start the DHT lookup right away; meanwhile try remembered LAN addresses.
    log('Looking up the server on the IPFS network…')
    const lookup = findAddrs(serverName, { routers, log, signal: abort.signal })
    lookup.catch(() => {})
    const remembered = relayOnly ? [] : rememberedAddrs(serverName)
    let dialed = await dialRemembered(node, serverName, remembered, { log, signal: dialSignal })
    if (!dialed) {
      // Relay routes from the last visit: dialled together with whatever the
      // lookup finds, and if the lookup is slow or fails we go with them alone.
      const relays = rememberedRelays(serverName)
      let found = []
      if (relays.length) {
        found = await Promise.race([lookup.catch(() => []), new Promise(resolve => setTimeout(() => resolve(null), 8000))])
        if (found === null) { log('Lookup is slow; trying the remembered relay routes.'); found = [] }
      } else {
        found = await lookup
      }
      const addrs = [...new Set([...extraAddrs, ...found, ...relays])]
      if (addrs.length === 0) {
        throw new Error('The server could not be found. Make sure it is running and online. Right after starting it can take a minute or two to become findable.')
      }
      dialed = await dialServer(node, serverName, addrs, { log, relayOnly, signal: dialSignal })
    }
    const { conn, viaRelay } = dialed
    const res = await negotiate(conn, { clientId: clientId(), remember: isRemembered(), base: DIR, log, viaRelay, signal: AbortSignal.timeout(60000) })
    const base = res.config.base || ''
    if (!base.startsWith(DIR) || base === DIR || !base.endsWith('/') || OWN_FILES.has(base.slice(DIR.length, -1))) {
      res.pc.close()
      throw Object.assign(new Error(
        `This page is hosted at "${DIR}" but the server's app is served under "${base}". ` +
        `It must be a sub-folder of the page's folder: set the server's resolver page URL to ${location.origin}${DIR} so both match.`), { fatal: true })
    }
    appBase = base
    rememberAddrs(serverName, res.config.addrs)
    rememberRelays(serverName, res.config.addrs)
    saveServer(serverName, res.config.name)
    if (pc) pc.close()
    pc = res.pc
    watch(pc)
    attempt = 0
    log('Connected.')
    if (res.config.name) document.title = `${res.config.name} · p2p`
    announce()
    openFrame()
    ui.overlay.hidden = true
    setBadge('ok')
  } catch (err) {
    console.error(err)
    setBadge('off')
    const delay = err.fatal ? 0 : Math.min(60000, 5000 * attempt)
    showError(err.message || String(err), { retryIn: delay })
  } finally {
    abort.abort()
    connecting = false
    // The libp2p node was only needed for signaling.
    if (node) node.stop().catch(() => {})
  }
}

function watch (p) {
  let timer = null
  p.addEventListener('connectionstatechange', () => {
    if (p !== pc) return
    const st = p.connectionState
    if (st === 'connected') { clearTimeout(timer); setBadge('ok'); announce() } else if (st === 'disconnected') {
      setBadge('warn')
      timer = setTimeout(() => { if (p === pc && p.connectionState !== 'connected') connect() }, 8000)
    } else if (st === 'failed' || st === 'closed') {
      setBadge('warn')
      announce()
      connect()
    }
  })
}

// --- Remote app iframe ---------------------------------------------------

function frameTarget () {
  const p = new URLSearchParams(location.hash.slice(1)).get('p')
  const path = p && p.startsWith(appBase) ? p : appBase
  const u = new URL(path, location.origin)
  u.searchParams.set('__p2p_host', hostId)
  return u.pathname + u.search
}

function openFrame () {
  if (ui.frame.src) return // keep the user's place across reconnects
  ui.frame.src = frameTarget()
  ui.frame.hidden = false
  // Mirror the iframe location into our hash so a reload returns there.
  setInterval(() => {
    try {
      const l = ui.frame.contentWindow.location
      if (!l.pathname.startsWith(appBase)) return
      const h = '#p=' + encodeURIComponent(l.pathname + l.search.replace(/[?&]__p2p_host=[^&]*/, '').replace(/^&/, '?'))
      if (location.hash !== h) history.replaceState(null, '', location.pathname + location.search + h)
    } catch {}
  }, 1000)
}

// --- Boot ----------------------------------------------------------------

function setupDeviceControls () {
  dropUnrememberedData()
  renderRemember()
  ui.remember.addEventListener('change', () => setRemember(ui.remember.checked))
  ui.deviceRemember.addEventListener('change', () => setRemember(ui.deviceRemember.checked))
  ui.badge.addEventListener('click', () => { ui.device.hidden = !ui.device.hidden })
  let armed = null
  ui.forget.addEventListener('click', () => {
    if (!armed) {
      ui.forget.textContent = 'Click again to confirm'
      armed = setTimeout(() => { armed = null; ui.forget.textContent = 'Forget this device' }, 3000)
      return
    }
    forgetDevice()
  })
  // Another tab changing the choice (or forgetting) updates this one.
  addEventListener('storage', (e) => { if (e.key === REMEMBER_KEY || e.key === null) renderRemember() })
}

async function main () {
  setupDeviceControls()
  ui.retry.addEventListener('click', () => connect())
  if (!serverName) {
    renderPicker()
    return
  }
  ui.home.hidden = !savedServers().some(s => s.id !== serverName)
  // Servers that report no name get labelled with their app's page title.
  ui.frame.addEventListener('load', () => {
    try {
      const t = ui.frame.contentDocument?.title?.trim()
      const saved = savedServers().find(s => s.id === serverName)
      if (t && saved && !saved.name) saveServer(serverName, t)
    } catch {}
  })
  try {
    peerIdFromString(serverName)
  } catch {
    ui.overlay.hidden = false
    showError(`"${serverName}" is not a valid server id. It should be a libp2p PeerID like 12D3KooW….`)
    ui.retry.hidden = true
    return
  }
  ui.server.textContent = serverName
  ui.server.hidden = false
  try {
    await setupServiceWorker()
  } catch (err) {
    ui.overlay.hidden = false
    showError(err.message)
    ui.retry.hidden = true
    return
  }
  connect()
}

main()
