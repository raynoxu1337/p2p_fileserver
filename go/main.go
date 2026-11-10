// p2pnode makes any local HTTP server reachable from a plain web
// page without a domain, public IP or port forwarding.
//
//   - libp2p (IPFS Amino DHT + public circuit relays) gives the server a stable
//     identity (PeerID), makes it discoverable and reachable for a tiny
//     signaling exchange even behind NAT / CGNAT.
//   - Over that authenticated signaling stream the browser and server set up a
//     regular WebRTC DataChannel connection (ICE/STUN/optional TURN), which
//     carries the actual HTTP traffic. See bridge.go for the wire format.
//
// Status is reported to the Android app as JSON lines on stdout.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"
)

type config struct {
	keyPath     string
	cookiePath  string
	target      *url.URL
	basePath    string
	listenPort  int
	dnsServers  []string
	iceServers  []iceServer
	maxSessions int
	maxPeers    int  // libp2p connection manager high watermark
	dhtServer   bool // act as a DHT server instead of client only
	name        string
	child       []string
}

func main() {
	var (
		keyPath  = flag.String("key", "identity.key", "path of the libp2p identity key (must exist; see -gen-key)")
		cookies  = flag.String("cookies", "", "file to keep visitors' backend cookies in, so logins survive restarts (default: memory only)")
		target   = flag.String("target", "http://127.0.0.1:8085", "local HTTP server to expose")
		base     = flag.String("base", "", "URL path the local server is served under, a sub-folder of where the resolver page is hosted (e.g. /p2p_resolver/app/); the local server must use the same base path")
		port     = flag.Int("port", 4001, "TCP/UDP port for libp2p listeners (0 = random)")
		dns      = flag.String("dns", "", "comma separated DNS servers host:port (Android has no /etc/resolv.conf)")
		stun     = flag.String("stun", "stun:stun.l.google.com:19302,stun:stun.cloudflare.com:3478", "comma separated STUN urls")
		turn     = flag.String("turn", "", "optional TURN server: turn:host:port (or turns:)")
		turnUser = flag.String("turn-user", "", "TURN username")
		turnPass = flag.String("turn-pass", "", "TURN credential")
		maxSess  = flag.Int("max-sessions", 16, "max concurrent browser sessions")
		maxPeers = flag.Int("max-peers", 120, "soft limit of libp2p peer connections; the node trims back to about a third of it when exceeded")
		dhtSrv   = flag.Bool("dht-server", false, "act as a DHT server (answer other peers' queries); more traffic, only useful when the node is reachable from the internet. Default is client-only (peer-only mode)")
		name     = flag.String("name", "", "friendly server name shown in the browser")
		printID  = flag.Bool("print-id", false, "print the PeerID of the existing -key and exit")
		genKey   = flag.Bool("gen-key", false, "generate a NEW identity at -key (replacing any existing one, which changes the PeerID), print its PeerID and exit")
		watchIn  = flag.Bool("stdin-watchdog", false, "exit when stdin is closed (used by the Android app so the node dies with it)")
	)
	flag.Usage = func() {
		fmt.Fprintf(os.Stderr, "usage: %s [flags] [-- server-command args...]\n\nIf a server command is given it is run (and restarted) as a child process.\n\n", os.Args[0])
		flag.PrintDefaults()
	}
	flag.Parse()
	log.SetFlags(log.Ltime)
	log.SetOutput(os.Stderr)

	if *genKey {
		id, err := generateKey(*keyPath)
		if err != nil {
			log.Fatal(err)
		}
		fmt.Println(id)
		return
	}
	if *printID {
		_, id, err := loadKey(*keyPath)
		if err != nil {
			log.Fatal(err)
		}
		fmt.Println(id)
		return
	}

	t, err := url.Parse(*target)
	if err != nil || t.Scheme != "http" || t.Host == "" {
		log.Fatalf("invalid -target %q", *target)
	}
	b := *base
	if strings.TrimSpace(b) == "" {
		log.Fatal("-base is required (e.g. -base /p2p_resolver/app/)")
	}
	if !strings.HasPrefix(b, "/") {
		b = "/" + b
	}
	if !strings.HasSuffix(b, "/") {
		b += "/"
	}
	cfg := config{
		keyPath:     *keyPath,
		cookiePath:  *cookies,
		target:      t,
		basePath:    b,
		listenPort:  *port,
		dnsServers:  splitList(*dns),
		maxSessions: *maxSess,
		maxPeers:    *maxPeers,
		dhtServer:   *dhtSrv,
		name:        *name,
		child:       flag.Args(),
	}
	if s := splitList(*stun); len(s) > 0 {
		cfg.iceServers = append(cfg.iceServers, iceServer{URLs: s})
	}
	if *turn != "" {
		cfg.iceServers = append(cfg.iceServers, iceServer{URLs: splitList(*turn), Username: *turnUser, Credential: *turnPass})
	}

	setupNetworking(cfg.dnsServers)

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	// The Android app closes our stdin when it wants us gone; also covers the
	// app process being killed (pipe closes).
	if *watchIn {
		go watchStdin(cancel)
	}

	if err := run(ctx, cfg); err != nil {
		emit("fatal", map[string]any{"error": err.Error()})
		log.Fatal(err)
	}
}

func watchStdin(cancel context.CancelFunc) {
	buf := make([]byte, 64)
	for {
		if _, err := os.Stdin.Read(buf); err != nil {
			cancel()
			return
		}
	}
}

func run(ctx context.Context, cfg config) error {
	node, err := newNode(ctx, cfg)
	if err != nil {
		return err
	}
	defer node.Close()

	sig := newSignaler(cfg, node.host)
	defer sig.Close()

	emit("ready", map[string]any{"peerId": node.host.ID().String()})
	log.Printf("PeerID %s", node.host.ID())

	go node.maintain(ctx)
	go node.reportTraffic(ctx)
	childDone := make(chan struct{})
	if len(cfg.child) > 0 {
		go func() {
			defer close(childDone)
			superviseChild(ctx, cfg.child, cfg.target.String()+cfg.basePath)
		}()
	} else {
		close(childDone)
	}

	tick := time.NewTicker(15 * time.Second)
	defer tick.Stop()
	for {
		node.reportStatus(sig.ActiveSessions())
		select {
		case <-ctx.Done():
			// Let the backend shut down cleanly (it got SIGTERM via ctx).
			select {
			case <-childDone:
			case <-time.After(7 * time.Second):
			}
			emit("stopped", nil)
			return nil
		case <-tick.C:
		}
	}
}

var emitMu sync.Mutex

// emit writes one machine-readable status line to stdout for the Android app.
func emit(event string, fields map[string]any) {
	m := map[string]any{"event": event, "time": time.Now().Unix()}
	for k, v := range fields {
		m[k] = v
	}
	b, _ := json.Marshal(m)
	emitMu.Lock()
	defer emitMu.Unlock()
	os.Stdout.Write(append(b, '\n'))
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}
