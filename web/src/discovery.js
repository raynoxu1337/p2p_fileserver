// Finding a server's current addresses from its PeerID, using public
// "delegated routing" HTTP endpoints that query the IPFS Amino DHT for us.
import { CID } from 'multiformats/cid'
import { sha256 } from 'multiformats/hashes/sha2'

export const DEFAULT_ROUTERS = ['https://delegated-ipfs.dev']

const RAW_CODEC = 0x55

// Generic protocol namespace, no deployment-specific secret.
// Must match ProtocolNamespace / NameCID in go/signal.go and go/host.go.
export const NAMESPACE = 'p2p-http:'

export async function nameCID (peerId) {
  const digest = await sha256.digest(new TextEncoder().encode(NAMESPACE + peerId))
  return CID.createV1(RAW_CODEC, digest)
}

async function getJSON (url, signal) {
  // Cache-buster: the CDN in front of delegated-ipfs.dev can serve a stale
  // copy without CORS headers, and relay addresses change anyway.
  url += (url.includes('?') ? '&' : '?') + 'nocache=' + Math.random().toString(36).slice(2)
  const res = await fetch(url, { headers: { Accept: 'application/json' }, signal, cache: 'no-store' })
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`${new URL(url).host} answered ${res.status}`)
  return res.json()
}

function collect (records, peerId, into) {
  for (const r of records ?? []) {
    if ((r.ID ?? r.id) !== peerId) continue
    for (const a of r.Addrs ?? r.addrs ?? []) into.add(a)
  }
}

/**
 * Returns the multiaddr strings known for peerId (may be empty).
 * Tries a direct peer lookup first, then the provider record the server
 * publishes under nameCID(peerId).
 */
export async function findAddrs (peerId, { routers = DEFAULT_ROUTERS, signal, log = () => {} } = {}) {
  const addrs = new Set()
  const cid = await nameCID(peerId)
  for (const base of routers) {
    try {
      log(`Asking ${new URL(base).host} for ${peerId.slice(0, 12)}…`)
      const peers = await getJSON(`${base}/routing/v1/peers/${peerId}`, signal)
      collect(peers?.Peers, peerId, addrs)
      if (addrs.size === 0) {
        log('No peer record yet, trying provider record…')
        const provs = await getJSON(`${base}/routing/v1/providers/${cid}`, signal)
        collect(provs?.Providers, peerId, addrs)
      }
    } catch (err) {
      if (signal?.aborted) throw err
      log(`Lookup via ${base} failed: ${err.message}`)
    }
    if (addrs.size > 0) break
  }
  return [...addrs]
}
