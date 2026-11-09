package main

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strings"
	"sync"
	"time"
)

// cookieStore keeps the backend's cookies on behalf of each browser.
//
// A Service Worker can't read or set cookies (and session cookies are usually
// HttpOnly anyway), so the node holds them, keyed by a random client id the
// page keeps in localStorage. With a path set they are saved to disk, so
// visitors stay logged in across node restarts (network changes, app
// restarts, reboots); without one they live in memory only.
//
// Every browser talks to the same single backend, so cookies are scoped by
// path only (Domain is ignored).
type cookieStore struct {
	path string

	mu      sync.Mutex
	clients map[string]*clientJar
	timer   *time.Timer
}

const (
	maxClients    = 256
	maxCookies    = 64 // per client
	clientIdleTTL = 60 * 24 * time.Hour
	saveDelay     = 2 * time.Second
)

type storedCookie struct {
	Name     string    `json:"name"`
	Value    string    `json:"value"`
	Path     string    `json:"path"`
	Expires  time.Time `json:"expires,omitzero"` // zero = session cookie
	HttpOnly bool      `json:"httpOnly,omitempty"`
}

type clientJar struct {
	store    *cookieStore
	List     []storedCookie `json:"cookies"`
	LastUsed time.Time      `json:"lastUsed"`
}

func newCookieStore(path string) *cookieStore {
	cs := &cookieStore{path: path, clients: map[string]*clientJar{}}
	if path == "" {
		return cs
	}
	b, err := os.ReadFile(path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			log.Printf("cookies: %v", err)
		}
		return cs
	}
	var saved map[string]*clientJar
	if err := json.Unmarshal(b, &saved); err != nil {
		log.Printf("cookies: ignoring corrupt %s: %v", path, err)
		return cs
	}
	now := time.Now()
	for id, j := range saved {
		if j == nil || now.Sub(j.LastUsed) > clientIdleTTL {
			continue
		}
		j.store = cs
		j.prune(now)
		if len(j.List) > 0 {
			cs.clients[id] = j
		}
	}
	log.Printf("cookies: restored sessions for %d browser(s)", len(cs.clients))
	return cs
}

// jar returns the cookie jar for one browser, creating it if needed.
func (cs *cookieStore) jar(client string) http.CookieJar {
	cs.mu.Lock()
	defer cs.mu.Unlock()
	j, ok := cs.clients[client]
	if !ok {
		if len(cs.clients) >= maxClients {
			var oldest string
			for id, c := range cs.clients {
				if oldest == "" || c.LastUsed.Before(cs.clients[oldest].LastUsed) {
					oldest = id
				}
			}
			delete(cs.clients, oldest)
		}
		j = &clientJar{store: cs}
		cs.clients[client] = j
	}
	j.LastUsed = time.Now()
	return j
}

func (j *clientJar) SetCookies(u *url.URL, cookies []*http.Cookie) {
	cs := j.store
	cs.mu.Lock()
	defer cs.mu.Unlock()
	now := time.Now()
	for _, c := range cookies {
		if c.Name == "" {
			continue
		}
		path := c.Path
		if path == "" || path[0] != '/' {
			path = defaultCookiePath(u.Path)
		}
		var exp time.Time
		remove := false
		switch {
		case c.MaxAge < 0:
			remove = true
		case c.MaxAge > 0:
			exp = now.Add(time.Duration(c.MaxAge) * time.Second)
		case !c.Expires.IsZero():
			exp = c.Expires
			remove = !exp.After(now)
		}
		j.List = slices.DeleteFunc(j.List, func(s storedCookie) bool { return s.Name == c.Name && s.Path == path })
		if !remove {
			j.List = append(j.List, storedCookie{Name: c.Name, Value: c.Value, Path: path, Expires: exp, HttpOnly: c.HttpOnly})
		}
	}
	if len(j.List) > maxCookies {
		j.List = j.List[len(j.List)-maxCookies:]
	}
	j.LastUsed = now
	cs.scheduleSave()
}

func (j *clientJar) Cookies(u *url.URL) []*http.Cookie {
	cs := j.store
	cs.mu.Lock()
	defer cs.mu.Unlock()
	now := time.Now()
	if j.prune(now) {
		cs.scheduleSave()
	}
	var match []storedCookie
	for _, c := range j.List {
		if pathMatch(u.Path, c.Path) {
			match = append(match, c)
		}
	}
	// RFC 6265: longer paths first.
	sort.SliceStable(match, func(a, b int) bool { return len(match[a].Path) > len(match[b].Path) })
	out := make([]*http.Cookie, len(match))
	for i, c := range match {
		out[i] = &http.Cookie{Name: c.Name, Value: c.Value}
	}
	return out
}

// prune drops expired cookies; reports whether anything changed. cs.mu held.
func (j *clientJar) prune(now time.Time) bool {
	n := len(j.List)
	j.List = slices.DeleteFunc(j.List, func(c storedCookie) bool { return !c.Expires.IsZero() && !c.Expires.After(now) })
	return len(j.List) != n
}

// scheduleSave coalesces writes; cs.mu held.
func (cs *cookieStore) scheduleSave() {
	if cs.path == "" || cs.timer != nil {
		return
	}
	cs.timer = time.AfterFunc(saveDelay, cs.Flush)
}

// Flush writes the store to disk now (if persistent).
func (cs *cookieStore) Flush() {
	if cs.path == "" {
		return
	}
	cs.mu.Lock()
	if cs.timer != nil {
		cs.timer.Stop()
		cs.timer = nil
	}
	out := map[string]*clientJar{}
	for id, j := range cs.clients {
		if len(j.List) > 0 {
			out[id] = j
		}
	}
	b, err := json.Marshal(out)
	cs.mu.Unlock()
	if err != nil {
		log.Printf("cookies: %v", err)
		return
	}
	if err := writeFileAtomic(cs.path, b, 0o600); err != nil {
		log.Printf("cookies: save: %v", err)
	}
}

func writeFileAtomic(path string, b []byte, perm os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, perm); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func defaultCookiePath(p string) string {
	i := strings.LastIndex(p, "/")
	if i <= 0 {
		return "/"
	}
	return p[:i]
}

func pathMatch(reqPath, cookiePath string) bool {
	if reqPath == "" {
		reqPath = "/"
	}
	if reqPath == cookiePath {
		return true
	}
	if !strings.HasPrefix(reqPath, cookiePath) {
		return false
	}
	return strings.HasSuffix(cookiePath, "/") || reqPath[len(cookiePath)] == '/'
}
