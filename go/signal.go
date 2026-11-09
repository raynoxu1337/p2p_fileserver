package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-msgio"
	manet "github.com/multiformats/go-multiaddr/net"
	"github.com/pion/webrtc/v4"
)

// ProtocolNamespace prefixes everything this node puts on the network. It is
// generic: any HTTP service can be exposed this way and any copy of the
// resolver page can reach it. Must match web/src/tunnel.js and discovery.js.
const ProtocolNamespace = "p2p-http:"

// SignalProtocol is the libp2p protocol the browser opens to negotiate a
// WebRTC connection. Must match web/src/tunnel.js.
const SignalProtocol = "/p2p-http/signal/1.0.0"

// ProtocolVersion is exchanged in hello/config ("v") so a page and a server
// from different releases fail with a clear message instead of misbehaving.
// Bump it for incompatible changes to the signaling or bridge wire format;
// MinClientVersion is the oldest page this server still works with.
// Must match web/src/tunnel.js.
const (
	ProtocolVersion  = 1
	MinClientVersion = 1
)

const maxSignalMsg = 64 << 10

type iceServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username,omitempty"`
	Credential string   `json:"credential,omitempty"`
}

// Signaling messages (length-prefixed JSON, unsigned varint prefix):
//
//	browser → {"type":"hello","v":1,"client":"<random id>","base":"<page folder, e.g. /p2p_resolver/>"}
//	server  → {"type":"config","v":1,"base":"<path the app is served under>","iceServers":[...],"name":"...",
//	           "addrs":["<multiaddr>",...]}
//	browser → {"type":"offer","sdp":"..."}      (complete, non-trickle ICE)
//	server  → {"type":"answer","sdp":"..."}     or {"type":"error","error":"..."}
//
// The page proxies every request under config.base, which must be a
// sub-folder of the page's own folder (the Service Worker's scope).
//
// config.addrs lists the node's current browser-dialable addresses: direct
// ones (LAN included; the public DHT strips private addresses, so this is the
// only way a browser learns them) and relayed ones. The page remembers them
// and dials them first next time: near-instant on the same network, and away
// from home it doesn't depend on the DHT lookup succeeding.
type sigMsg struct {
	Type       string      `json:"type"`
	V          int         `json:"v,omitempty"`
	Client     string      `json:"client,omitempty"`
	Base       string      `json:"base,omitempty"`
	SDP        string      `json:"sdp,omitempty"`
	ICEServers []iceServer `json:"iceServers,omitempty"`
	Name       string      `json:"name,omitempty"`
	Addrs      []string    `json:"addrs,omitempty"`
	Error      string      `json:"error,omitempty"`
}

type signaler struct {
	cfg    config
	host   host.Host
	api    *webrtc.API
	client *http.Client

	cookies *cookieStore

	mu       sync.Mutex
	sessions map[*webrtc.PeerConnection]struct{}
}

func newSignaler(cfg config, h host.Host) *signaler {
	se := webrtc.SettingEngine{}
	// We never send media; keep ICE snappy on flaky mobile links.
	se.SetICETimeouts(8*time.Second, 30*time.Second, 2*time.Second)
	s := &signaler{
		cfg:  cfg,
		host: h,
		api:  webrtc.NewAPI(webrtc.WithSettingEngine(se)),
		client: &http.Client{
			Transport: &http.Transport{
				MaxIdleConnsPerHost: 32,
				IdleConnTimeout:     90 * time.Second,
				// Responses are handed to a Service Worker which can't undo
				// Content-Encoding, so let Go decompress transparently.
				DisableCompression: false,
			},
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
		sessions: map[*webrtc.PeerConnection]struct{}{},
		cookies:  newCookieStore(cfg.cookiePath),
	}
	h.SetStreamHandler(SignalProtocol, s.handleStream)
	return s
}

func (s *signaler) Close() {
	s.mu.Lock()
	for pc := range s.sessions {
		pc.Close()
	}
	s.mu.Unlock()
	s.cookies.Flush()
}

func (s *signaler) ActiveSessions() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.sessions)
}

func (s *signaler) handleStream(st network.Stream) {
	defer st.Close()
	remote := st.Conn().RemotePeer()
	st.SetDeadline(time.Now().Add(45 * time.Second))
	r := msgio.NewVarintReaderSize(st, maxSignalMsg)
	w := msgio.NewVarintWriter(st)

	send := func(m sigMsg) error {
		b, _ := json.Marshal(m)
		return w.WriteMsg(b)
	}
	recv := func() (sigMsg, error) {
		var m sigMsg
		b, err := r.ReadMsg()
		if err != nil {
			return m, err
		}
		defer r.ReleaseMsg(b)
		err = json.Unmarshal(b, &m)
		return m, err
	}
	fail := func(err error) {
		log.Printf("signal %s: %v", remote.ShortString(), err)
		_ = send(sigMsg{Type: "error", Error: err.Error()})
	}

	hello, err := recv()
	if err != nil || hello.Type != "hello" {
		fail(fmt.Errorf("expected hello: %v", err))
		return
	}
	if hello.V < MinClientVersion {
		fail(fmt.Errorf("this resolver page is outdated (protocol v%d, server needs at least v%d): upload the current p2p_resolver folder again", hello.V, MinClientVersion))
		return
	}
	if len(hello.Client) < 16 || len(hello.Client) > 128 {
		fail(errors.New("bad client id"))
		return
	}
	cfgMsg := sigMsg{Type: "config", V: ProtocolVersion, Base: s.cfg.basePath, ICEServers: s.cfg.iceServers, Name: s.cfg.name, Addrs: s.advertisedAddrs()}
	if err := send(cfgMsg); err != nil {
		return
	}
	offer, err := recv()
	if err != nil || offer.Type != "offer" {
		fail(fmt.Errorf("expected offer: %v", err))
		return
	}

	if s.ActiveSessions() >= s.cfg.maxPeers {
		fail(errors.New("server busy: too many sessions"))
		return
	}
	pc, answer, err := s.newSession(hello.Client, offer.SDP)
	if err != nil {
		fail(err)
		return
	}
	if err := send(sigMsg{Type: "answer", SDP: answer}); err != nil {
		pc.Close()
		return
	}
	log.Printf("session from %s (via %s)", remote.ShortString(), st.Conn().RemoteMultiaddr())
}

func (s *signaler) newSession(client, offerSDP string) (*webrtc.PeerConnection, string, error) {
	var ice []webrtc.ICEServer
	for _, is := range s.cfg.iceServers {
		ice = append(ice, webrtc.ICEServer{URLs: is.URLs, Username: is.Username, Credential: is.Credential})
	}
	pc, err := s.api.NewPeerConnection(webrtc.Configuration{ICEServers: ice})
	if err != nil {
		return nil, "", err
	}
	s.mu.Lock()
	s.sessions[pc] = struct{}{}
	s.mu.Unlock()
	emit("session", map[string]any{"active": s.ActiveSessions()})

	sess := &session{s: s, jar: s.cookies.jar(client), ctx: context.Background()}
	var cancel context.CancelFunc
	sess.ctx, cancel = context.WithCancel(context.Background())

	cleanup := sync.OnceFunc(func() {
		cancel()
		pc.Close()
		s.mu.Lock()
		delete(s.sessions, pc)
		s.mu.Unlock()
		emit("session", map[string]any{"active": s.ActiveSessions()})
	})

	pc.OnConnectionStateChange(func(st webrtc.PeerConnectionState) {
		log.Printf("webrtc session: %s", st)
		switch st {
		case webrtc.PeerConnectionStateFailed, webrtc.PeerConnectionStateClosed:
			go cleanup()
		case webrtc.PeerConnectionStateDisconnected:
			// Give ICE a moment to recover (e.g. a phone switching Wi-Fi → LTE).
			time.AfterFunc(20*time.Second, func() {
				if pc.ConnectionState() == webrtc.PeerConnectionStateDisconnected {
					cleanup()
				}
			})
		}
	})
	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		if dc.Label() == "http" {
			sess.serve(dc)
		}
		// "ctrl" channel exists only so the offer contains an SCTP section.
	})

	fail := func(err error) (*webrtc.PeerConnection, string, error) {
		cleanup()
		return nil, "", err
	}
	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: offerSDP}); err != nil {
		return fail(fmt.Errorf("bad offer: %w", err))
	}
	ans, err := pc.CreateAnswer(nil)
	if err != nil {
		return fail(err)
	}
	gathered := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(ans); err != nil {
		return fail(err)
	}
	select {
	case <-gathered:
	case <-time.After(10 * time.Second):
		log.Printf("ICE gathering slow; sending partial candidates")
	}
	// Drop sessions that never connect.
	time.AfterFunc(60*time.Second, func() {
		if st := pc.ConnectionState(); st == webrtc.PeerConnectionStateNew || st == webrtc.PeerConnectionStateConnecting {
			cleanup()
		}
	})
	return pc, pc.LocalDescription().SDP, nil
}

// advertisedAddrs returns the addresses a browser could dial: direct ones
// (LAN or public) and relayed ones, loopback/link-local excluded.
func (s *signaler) advertisedAddrs() []string {
	var out []string
	for _, a := range s.host.Addrs() {
		if !browserDialable(a) || manet.IsIPLoopback(a) || manet.IsIP6LinkLocal(a) {
			continue
		}
		out = append(out, a.String())
	}
	return out
}
