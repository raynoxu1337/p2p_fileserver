package main

import (
	"context"
	"sync/atomic"
	"time"
)

// File-server traffic: HTTP payload carried over the WebRTC DataChannels
// (request heads and bodies in, response heads and bodies out). WebRTC
// framing/encryption overhead is not included. libp2p traffic (the upkeep of
// staying findable) is counted by node.bw instead.
var fileRx, fileTx atomic.Int64

// trafficInterval is how often the "traffic" event is emitted. Rates are the
// average over the last interval.
const trafficInterval = 2 * time.Second

// reportTraffic emits totals since start and current rates (bytes/s):
//
//	{"event":"traffic","fileRx":..,"fileTx":..,"fileRxRate":..,"fileTxRate":..,
//	 "netRx":..,"netTx":..,"netRxRate":..,"netTxRate":..}
func (n *node) reportTraffic(ctx context.Context) {
	t := time.NewTicker(trafficInterval)
	defer t.Stop()
	last := time.Now()
	var lastFRx, lastFTx, lastNRx, lastNTx int64
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-t.C:
			secs := now.Sub(last).Seconds()
			last = now
			frx, ftx := fileRx.Load(), fileTx.Load()
			bw := n.bw.GetBandwidthTotals()
			nrx, ntx := bw.TotalIn, bw.TotalOut
			rate := func(cur, prev int64) int64 {
				if secs <= 0 || cur < prev {
					return 0
				}
				return int64(float64(cur-prev) / secs)
			}
			emit("traffic", map[string]any{
				"fileRx": frx, "fileTx": ftx,
				"fileRxRate": rate(frx, lastFRx), "fileTxRate": rate(ftx, lastFTx),
				"netRx": nrx, "netTx": ntx,
				"netRxRate": rate(nrx, lastNRx), "netTxRate": rate(ntx, lastNTx),
			})
			lastFRx, lastFTx, lastNRx, lastNTx = frx, ftx, nrx, ntx
		}
	}
}
