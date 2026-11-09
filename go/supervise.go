//go:build linux || android

package main

import (
	"bufio"
	"context"
	"io"
	"log"
	"net/http"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

// superviseChild runs the local web server (the backend) as our child and
// restarts it if it crashes. Pdeathsig makes the kernel kill it if we die, so
// the app only has to manage one process and nothing is left holding the port.
func superviseChild(ctx context.Context, argv []string, healthURL string) {
	go probeHealth(ctx, healthURL)
	backoff := time.Second
	for ctx.Err() == nil {
		cmd := exec.CommandContext(ctx, argv[0], argv[1:]...)
		cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL, Setpgid: true}
		cmd.Cancel = func() error { return cmd.Process.Signal(syscall.SIGTERM) }
		cmd.WaitDelay = 5 * time.Second
		pr, pw := io.Pipe()
		cmd.Stdout, cmd.Stderr = pw, pw
		go relayLines(pr, filepath.Base(argv[0]))

		started := time.Now()
		emit("backend", map[string]any{"state": "starting"})
		err := cmd.Run()
		pw.Close()
		if ctx.Err() != nil {
			return
		}
		log.Printf("backend exited: %v", err)
		emit("backend", map[string]any{"state": "crashed", "error": errString(err)})
		if time.Since(started) > time.Minute {
			backoff = time.Second
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, time.Minute)
	}
}

func relayLines(r io.Reader, prefix string) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64<<10), 1<<20)
	for sc.Scan() {
		log.Printf("[%s] %s", prefix, stripANSI(sc.Text()))
	}
}

func probeHealth(ctx context.Context, url string) {
	c := &http.Client{Timeout: 4 * time.Second}
	last := ""
	for {
		state := "down"
		if req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil); err == nil {
			if resp, err := c.Do(req); err == nil {
				resp.Body.Close()
				if resp.StatusCode < 500 {
					state = "up"
				}
			}
		}
		if state != last {
			emit("backend", map[string]any{"state": state})
			last = state
		}
		wait := 10 * time.Second
		if state != "up" {
			wait = 2 * time.Second
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
	}
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func stripANSI(s string) string {
	if !strings.Contains(s, "\x1b[") {
		return s
	}
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		if s[i] == 0x1b && i+1 < len(s) && s[i+1] == '[' {
			j := i + 2
			for j < len(s) && (s[j] < '@' || s[j] > '~') {
				j++
			}
			i = j
			continue
		}
		b.WriteByte(s[i])
	}
	return b.String()
}
