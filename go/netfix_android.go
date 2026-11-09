//go:build android

package main

import (
	"context"
	"log"
	"net"
	"time"

	"github.com/wlynxg/anet"
)

// Android quirks for a plain (non-gomobile) Go binary:
//   - there is no /etc/resolv.conf, so the pure-Go resolver would query
//     127.0.0.1:53 and fail. Use the DNS servers the app passes in (from
//     Android's LinkProperties) with public fallbacks.
//   - since Android 11 netlink RTM_GETLINK is denied, so net.Interfaces /
//     net.InterfaceAddrs fail. anet implements them via RTM_GETADDR + ioctl.
//     (pion/webrtc already uses anet on android; we use it for libp2p addrs.)
func setupNetworking(dnsServers []string) {
	servers := append([]string{}, dnsServers...)
	servers = append(servers, "1.1.1.1:53", "8.8.8.8:53", "[2606:4700:4700::1111]:53")
	d := &net.Dialer{Timeout: 4 * time.Second}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var lastErr error
			for _, s := range servers {
				c, err := d.DialContext(ctx, network, s)
				if err == nil {
					return c, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
	if len(interfaceIPs()) == 0 {
		log.Printf("warning: could not enumerate network interfaces")
	}
}

func init() {
	interfaceIPs = func() []net.IP {
		addrs, err := anet.InterfaceAddrs()
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
}
