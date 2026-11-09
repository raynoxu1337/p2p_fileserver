package main

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/ipfs/go-cid"
	"github.com/libp2p/go-libp2p"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/event"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/p2p/host/autorelay"
	"github.com/libp2p/go-libp2p/p2p/net/connmgr"
	"github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/proto"
	ma "github.com/multiformats/go-multiaddr"
	manet "github.com/multiformats/go-multiaddr/net"
	mh "github.com/multiformats/go-multihash"
)

// NameCID is the content ID the node announces as a provider in the DHT, so a
// browser can find it through /routing/v1/providers/{cid} if a direct peer
// lookup fails. It is derived from the PeerID alone under the generic
// "p2p-http:" protocol namespace (no app- or deployment-specific secret).
// Must stay in sync with web/src/discovery.js.
func NameCID(id peer.ID) cid.Cid {
	sum := sha256.Sum256([]byte(ProtocolNamespace + id.String()))
	h, _ := mh.Encode(sum[:], mh.SHA2_256)
	return cid.NewCidV1(cid.Raw, h)
}

type node struct {
	host host.Host
	dht  *dht.IpfsDHT
	cfg  config

	mu         sync.Mutex
	relayAddrs []ma.Multiaddr
}

// loadKey reads an existing identity key. Keys are never created implicitly:
// the owner generates one explicitly (-gen-key) so the PeerID, and thus every
// shared link, only changes when they ask for it.
func loadKey(path string) (crypto.PrivKey, peer.ID, error) {
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, "", fmt.Errorf("no identity key at %s (create one with -gen-key)", path)
	} else if err != nil {
		return nil, "", err
	}
	k, err := crypto.UnmarshalPrivateKey(b)
	if err != nil {
		return nil, "", fmt.Errorf("corrupt identity key %s: %w", path, err)
	}
	id, err := peer.IDFromPrivateKey(k)
	return k, id, err
}

// generateKey creates a fresh Ed25519 identity and atomically replaces the
// key at path (if any). The old PeerID is gone for good afterwards.
func generateKey(path string) (peer.ID, error) {
	k, _, err := crypto.GenerateEd25519Key(nil)
	if err != nil {
		return "", err
	}
	b, err := crypto.MarshalPrivateKey(k)
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return "", err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return "", err
	}
	if err := os.Rename(tmp, path); err != nil {
		return "", err
	}
	return peer.IDFromPrivateKey(k)
}

// browserDialable reports whether a browser could open a connection to addr
// (WebRTC-direct, WebTransport or secure WebSockets).
func browserDialable(addr ma.Multiaddr) bool {
	s := addr.String()
	return strings.Contains(s, "/webrtc-direct/") ||
		strings.Contains(s, "/webtransport/") ||
		strings.Contains(s, "/wss") || strings.Contains(s, "/tls/ws") ||
		strings.Contains(s, "/tls/sni/")
}

func newNode(ctx context.Context, cfg config) (*node, error) {
	key, _, err := loadKey(cfg.keyPath)
	if err != nil {
		return nil, err
	}
	n := &node{cfg: cfg}

	p := cfg.listenPort
	listen := []string{
		fmt.Sprintf("/ip4/0.0.0.0/tcp/%d", p),
		fmt.Sprintf("/ip6/::/tcp/%d", p),
		fmt.Sprintf("/ip4/0.0.0.0/udp/%d/quic-v1", p),
		fmt.Sprintf("/ip6/::/udp/%d/quic-v1", p),
		fmt.Sprintf("/ip4/0.0.0.0/udp/%d/quic-v1/webtransport", p),
		fmt.Sprintf("/ip6/::/udp/%d/quic-v1/webtransport", p),
		fmt.Sprintf("/ip4/0.0.0.0/udp/%d/webrtc-direct", p),
		fmt.Sprintf("/ip6/::/udp/%d/webrtc-direct", p),
	}

	cm, err := connmgr.NewConnManager(40, 120, connmgr.WithGracePeriod(time.Minute))
	if err != nil {
		return nil, err
	}

	var dhtRef *dht.IpfsDHT
	var hostRef host.Host

	opts := []libp2p.Option{
		libp2p.Identity(key),
		libp2p.ListenAddrStrings(listen...),
		libp2p.DefaultTransports,
		libp2p.DefaultSecurity,
		libp2p.DefaultMuxers,
		libp2p.ConnectionManager(cm),
		libp2p.NATPortMap(),
		libp2p.EnableHolePunching(),
		libp2p.EnableRelay(),
		// Phones are nearly always behind NAT/CGNAT and change networks. Always
		// hold relay reservations so the browser can reach us for signaling;
		// the bulk data path is WebRTC/ICE anyway, not this connection.
		libp2p.ForceReachabilityPrivate(),
		libp2p.EnableAutoRelayWithPeerSource(
			func(ctx context.Context, num int) <-chan peer.AddrInfo {
				return relayCandidates(ctx, hostRef, dhtRef, num)
			},
			autorelay.WithNumRelays(3),
			autorelay.WithMinCandidates(2),
			autorelay.WithBootDelay(5*time.Second),
			autorelay.WithBackoff(2*time.Minute),
			autorelay.WithMinInterval(15*time.Second),
		),
		libp2p.AddrsFactory(func(addrs []ma.Multiaddr) []ma.Multiaddr {
			return n.addrsFactory(addrs)
		}),
	}

	h, err := libp2p.New(opts...)
	if err != nil {
		return nil, err
	}
	hostRef = h
	n.host = h

	d, err := dht.New(h,
		dht.Mode(dht.ModeClient),
		dht.BootstrapPeers(dht.GetDefaultBootstrapPeerAddrInfos()...),
	)
	if err != nil {
		h.Close()
		return nil, err
	}
	dhtRef = d
	n.dht = d

	sub, err := h.EventBus().Subscribe(new(event.EvtAutoRelayAddrsUpdated))
	if err == nil {
		go func() {
			defer sub.Close()
			for {
				select {
				case <-ctx.Done():
					return
				case e, ok := <-sub.Out():
					if !ok {
						return
					}
					ev := e.(event.EvtAutoRelayAddrsUpdated)
					n.mu.Lock()
					n.relayAddrs = slices.Clone(ev.RelayAddrs)
					n.mu.Unlock()
					log.Printf("relay addresses updated: %d", len(ev.RelayAddrs))
				}
			}
		}()
	}
	return n, nil
}

// addrsFactory adds the device's interface addresses for listeners bound to
// 0.0.0.0/::. On Android 11+ Go can't enumerate interfaces through netlink,
// so libp2p would otherwise only know loopback (see netfix_android.go).
func (n *node) addrsFactory(addrs []ma.Multiaddr) []ma.Multiaddr {
	if n.host == nil {
		return addrs
	}
	ips := interfaceIPs()
	if len(ips) == 0 {
		return addrs
	}
	out := slices.Clone(addrs)
	for _, l := range n.host.Network().ListenAddresses() {
		first, rest := ma.SplitFirst(l)
		if first == nil {
			continue
		}
		ip, err := manet.ToIP(ma.Multiaddr{*first})
		if err != nil || !ip.IsUnspecified() {
			continue
		}
		v4 := ip.To4() != nil
		for _, a := range ips {
			if (a.To4() != nil) != v4 || a.IsLoopback() || a.IsLinkLocalUnicast() {
				continue
			}
			ipm, err := manet.FromIP(a)
			if err != nil {
				continue
			}
			out = append(out, ipm.Encapsulate(rest))
		}
	}
	return ma.Unique(out)
}

// relayCandidates feeds autorelay with connected peers that run a relay
// service and that a browser can dial. Relays only reachable over TCP/QUIC
// would be useless to the web page.
func relayCandidates(ctx context.Context, h host.Host, d *dht.IpfsDHT, num int) <-chan peer.AddrInfo {
	ch := make(chan peer.AddrInfo, num)
	go func() {
		defer close(ch)
		if h == nil {
			return
		}
		seen := map[peer.ID]bool{}
		var ids []peer.ID
		ids = append(ids, h.Network().Peers()...)
		if d != nil {
			ids = append(ids, d.RoutingTable().ListPeers()...)
		}
		sent := 0
		for _, id := range ids {
			if sent >= num || ctx.Err() != nil {
				return
			}
			if seen[id] {
				continue
			}
			seen[id] = true
			if ok, _ := h.Peerstore().SupportsProtocols(id, proto.ProtoIDv2Hop); len(ok) == 0 {
				continue
			}
			addrs := h.Peerstore().Addrs(id)
			if !slices.ContainsFunc(addrs, browserDialable) {
				continue
			}
			select {
			case ch <- peer.AddrInfo{ID: id, Addrs: addrs}:
				sent++
			case <-ctx.Done():
				return
			}
		}
	}()
	return ch
}

// maintain keeps the node discoverable: it stays connected to the DHT peers
// closest to its own ID (they answer FindPeer queries for us, which is what
// the browser's delegated lookup uses) and periodically re-announces the
// provider record as a fallback.
func (n *node) maintain(ctx context.Context) {
	if err := n.dht.Bootstrap(ctx); err != nil {
		log.Printf("dht bootstrap: %v", err)
	}
	for _, pi := range dht.GetDefaultBootstrapPeerAddrInfos() {
		go func(pi peer.AddrInfo) {
			cctx, cancel := context.WithTimeout(ctx, 20*time.Second)
			defer cancel()
			if err := n.host.Connect(cctx, pi); err != nil {
				log.Printf("bootstrap %s: %v", pi.ID.ShortString(), err)
			}
		}(pi)
	}

	var lastProvide time.Time
	interval := 20 * time.Second
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(interval):
		}
		if len(n.host.Network().Peers()) == 0 {
			continue // offline; retry soon
		}
		n.announceSelf(ctx)
		if time.Since(lastProvide) > 6*time.Hour {
			pctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
			if err := n.dht.Provide(pctx, NameCID(n.host.ID()), true); err != nil {
				log.Printf("provide: %v", err)
			} else {
				lastProvide = time.Now()
				log.Printf("announced provider record %s", NameCID(n.host.ID()))
			}
			cancel()
		}
		interval = 5 * time.Minute
	}
}

func (n *node) announceSelf(ctx context.Context) {
	cctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	closest, err := n.dht.GetClosestPeers(cctx, string(n.host.ID()))
	if err != nil {
		log.Printf("self lookup: %v", err)
		return
	}
	for i, p := range closest {
		if i >= 8 {
			break
		}
		n.host.ConnManager().Protect(p, "p2p-http-closest")
		if n.host.Network().Connectedness(p) != network.Connected {
			go func(p peer.ID) {
				c, cancel := context.WithTimeout(ctx, 20*time.Second)
				defer cancel()
				_ = n.host.Connect(c, peer.AddrInfo{ID: p})
			}(p)
		}
	}
}

func (n *node) reportStatus(sessions int) {
	addrs := n.host.Addrs()
	var all, browser []string
	public := 0
	for _, a := range addrs {
		all = append(all, a.String())
		if browserDialable(a) {
			browser = append(browser, a.String())
			if manet.IsPublicAddr(a) {
				public++
			}
		}
	}
	n.mu.Lock()
	relays := len(n.relayAddrs)
	n.mu.Unlock()
	emit("status", map[string]any{
		"peerId":       n.host.ID().String(),
		"peers":        len(n.host.Network().Peers()),
		"relayAddrs":   relays,
		"browserAddrs": browser,
		"addrs":        all,
		"sessions":     sessions,
		"online":       relays > 0 || public > 0,
	})
}

func (n *node) Close() {
	n.dht.Close()
	n.host.Close()
}

// interfaceIPs is overridden on Android; elsewhere the stdlib works.
var interfaceIPs = func() []net.IP {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return nil
	}
	var out []net.IP
	for _, a := range addrs {
		if ipn, ok := a.(*net.IPNet); ok {
			out = append(out, ipn.IP)
		}
	}
	return out
}
