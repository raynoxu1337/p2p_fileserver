package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"

	"github.com/pion/webrtc/v4"
)

// HTTP over WebRTC DataChannels. One DataChannel (label "http") per request:
//
//	browser → text   {"method":"GET","url":"/p2p_resolver/app/api/..","headers":[["k","v"]],"body":true}
//	browser → binary request body chunks (only if "body":true)
//	browser → text   "end"                      (end of request body)
//	server  → text   {"status":200,"headers":[["k","v"]]}
//	server  → binary response body chunks
//	server  → text   "end" | "error:<message>"
//	browser → text   "ack:<n>"   cumulative response bytes consumed (flow control)
//
// Either side closing the channel aborts the exchange. The server never has
// more than sendWindow unacknowledged bytes in flight per request, so a slow
// consumer (e.g. a paused <video>) applies backpressure all the way to the
// file read instead of buffering the whole file in browser memory.
//
// Must match web/src/tunnel.js.

const (
	chunkSize   = 16 << 10 // safe across Chrome/Firefox/Safari SCTP message limits
	sendWindow  = 4 << 20
	maxBuffered = 1 << 20
	maxHeadSize = 64 << 10
)

type reqHead struct {
	Method  string      `json:"method"`
	URL     string      `json:"url"`
	Headers [][2]string `json:"headers"`
	Body    bool        `json:"body"`
}

type respHead struct {
	Status  int         `json:"status"`
	Headers [][2]string `json:"headers"`
}

type session struct {
	s   *signaler
	jar http.CookieJar
	ctx context.Context
}

var hopByHop = map[string]bool{
	"connection": true, "keep-alive": true, "proxy-connection": true, "te": true,
	"trailer": true, "transfer-encoding": true, "upgrade": true, "host": true,
	"proxy-authorization": true, "proxy-authenticate": true,
}

type exchange struct {
	sess   *session
	dc     *webrtc.DataChannel
	ctx    context.Context
	cancel context.CancelFunc

	started bool
	bodyW   *io.PipeWriter
	bodyR   *io.PipeReader

	acked  atomic.Int64
	wake   chan struct{} // ack received / buffer drained / closed
	closed atomic.Bool
}

func (sess *session) serve(dc *webrtc.DataChannel) {
	ctx, cancel := context.WithCancel(sess.ctx)
	ex := &exchange{sess: sess, dc: dc, ctx: ctx, cancel: cancel, wake: make(chan struct{}, 1)}
	dc.SetBufferedAmountLowThreshold(maxBuffered / 2)
	dc.OnBufferedAmountLow(ex.notify)
	dc.OnClose(func() {
		ex.closed.Store(true)
		cancel()
		if ex.bodyW != nil {
			ex.bodyW.CloseWithError(errors.New("channel closed"))
		}
		ex.notify()
	})
	dc.OnMessage(ex.onMessage)
}

func (ex *exchange) notify() {
	select {
	case ex.wake <- struct{}{}:
	default:
	}
}

// onMessage runs on the channel's read goroutine; blocking here (writing the
// request body into the pipe) back-pressures the browser via SCTP.
func (ex *exchange) onMessage(m webrtc.DataChannelMessage) {
	fileRx.Add(int64(len(m.Data)))
	if !ex.started {
		if !m.IsString || len(m.Data) > maxHeadSize {
			ex.dc.Close()
			return
		}
		var h reqHead
		if err := json.Unmarshal(m.Data, &h); err != nil {
			ex.dc.Close()
			return
		}
		ex.started = true
		var body io.Reader
		if h.Body {
			ex.bodyR, ex.bodyW = io.Pipe()
			body = ex.bodyR
		}
		go ex.do(h, body)
		return
	}
	if m.IsString {
		s := string(m.Data)
		switch {
		case s == "end":
			if ex.bodyW != nil {
				ex.bodyW.Close()
			}
		case strings.HasPrefix(s, "ack:"):
			if n, err := strconv.ParseInt(s[4:], 10, 64); err == nil && n > ex.acked.Load() {
				ex.acked.Store(n)
				ex.notify()
			}
		}
		return
	}
	if ex.bodyW != nil {
		_, _ = ex.bodyW.Write(m.Data)
	}
}

func (ex *exchange) do(h reqHead, body io.Reader) {
	defer ex.cancel()
	defer func() {
		if ex.bodyR != nil {
			ex.bodyR.CloseWithError(io.ErrClosedPipe)
		}
	}()
	target := ex.sess.s.cfg.target

	if !strings.HasPrefix(h.URL, "/") || strings.HasPrefix(h.URL, "//") {
		ex.replyError(400, "bad url")
		return
	}
	u, err := url.Parse(target.Scheme + "://" + target.Host + h.URL)
	if err != nil || u.Host != target.Host {
		ex.replyError(400, "bad url")
		return
	}
	method := strings.ToUpper(h.Method)
	if method == "" || method == "CONNECT" || method == "TRACE" {
		ex.replyError(405, "method not allowed")
		return
	}

	req, err := http.NewRequestWithContext(ex.ctx, method, u.String(), body)
	if err != nil {
		ex.replyError(400, err.Error())
		return
	}
	for _, kv := range h.Headers {
		k := strings.ToLower(kv[0])
		switch {
		case hopByHop[k], k == "cookie", k == "accept-encoding", k == "origin":
			continue
		case k == "content-length":
			if n, err := strconv.ParseInt(kv[1], 10, 64); err == nil && body != nil {
				req.ContentLength = n
			}
			continue
		}
		req.Header.Add(kv[0], kv[1])
	}
	if body == nil {
		req.ContentLength = 0
	} else if req.ContentLength == 0 {
		req.ContentLength = -1 // unknown, chunked
	}
	for _, c := range ex.sess.jar.Cookies(u) {
		req.AddCookie(c)
	}

	resp, err := ex.sess.s.client.Do(req)
	if err != nil {
		if ex.ctx.Err() == nil {
			log.Printf("proxy %s %s: %v", method, h.URL, err)
			ex.replyError(502, "could not reach the local server: "+err.Error())
		}
		return
	}
	defer resp.Body.Close()
	if cs := resp.Cookies(); len(cs) > 0 {
		ex.sess.jar.SetCookies(u, cs)
	}

	rh := respHead{Status: resp.StatusCode}
	for k, vs := range resp.Header {
		lk := strings.ToLower(k)
		if hopByHop[lk] || lk == "set-cookie" {
			continue
		}
		for _, v := range vs {
			if lk == "location" {
				// Absolute redirects to the local server become path-only.
				origin := target.Scheme + "://" + target.Host
				v = strings.TrimPrefix(v, origin)
			}
			rh.Headers = append(rh.Headers, [2]string{k, v})
		}
	}
	hb, _ := json.Marshal(rh)
	if err := ex.dc.SendText(string(hb)); err != nil {
		return
	}
	fileTx.Add(int64(len(hb)))

	var sent int64
	buf := make([]byte, chunkSize)
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if err := ex.waitSendable(sent); err != nil {
				return
			}
			if err := ex.dc.Send(buf[:n]); err != nil {
				return
			}
			sent += int64(n)
			fileTx.Add(int64(n))
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			if ex.ctx.Err() == nil {
				_ = ex.dc.SendText("error:" + rerr.Error())
				ex.dc.GracefulClose()
			}
			return
		}
	}
	_ = ex.dc.SendText("end")
	ex.dc.GracefulClose()
}

// waitSendable blocks until the flow-control window and the SCTP send buffer
// both have room.
func (ex *exchange) waitSendable(sent int64) error {
	for {
		if ex.closed.Load() || ex.ctx.Err() != nil {
			return errors.New("closed")
		}
		if sent-ex.acked.Load() < sendWindow && ex.dc.BufferedAmount() < maxBuffered {
			return nil
		}
		select {
		case <-ex.wake:
		case <-ex.ctx.Done():
			return ex.ctx.Err()
		}
	}
}

func (ex *exchange) replyError(status int, msg string) {
	hb, _ := json.Marshal(respHead{Status: status, Headers: [][2]string{{"Content-Type", "text/plain; charset=utf-8"}}})
	if ex.dc.SendText(string(hb)) != nil {
		return
	}
	_ = ex.dc.Send([]byte(fmt.Sprintf("%d: %s\n", status, msg)))
	_ = ex.dc.SendText("end")
	ex.dc.GracefulClose()
}
