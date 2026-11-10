package main

import (
	"net/http"
	"net/url"
	"path/filepath"
	"testing"
	"time"
)

func names(cs []*http.Cookie) map[string]string {
	m := map[string]string{}
	for _, c := range cs {
		m[c.Name] = c.Value
	}
	return m
}

func TestCookieStorePersistsAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cookies.json")
	u, _ := url.Parse("http://127.0.0.1:1/app/api/login")
	page, _ := url.Parse("http://127.0.0.1:1/app/files/x")
	other, _ := url.Parse("http://127.0.0.1:1/application")

	s1 := newCookieStore(path)
	s1.jar("client-a", true).SetCookies(u, []*http.Cookie{
		{Name: "auth", Value: "tok", Path: "/app", HttpOnly: true}, // session cookie
		{Name: "pref", Value: "1", Path: "/app/", MaxAge: 3600},
		{Name: "old", Value: "x", Path: "/app", Expires: time.Now().Add(-time.Hour)},
	})
	s1.jar("client-b", true).SetCookies(u, []*http.Cookie{{Name: "auth", Value: "other", Path: "/"}})
	s1.Flush()

	s2 := newCookieStore(path) // "restart"
	got := names(s2.jar("client-a", true).Cookies(page))
	if got["auth"] != "tok" || got["pref"] != "1" || len(got) != 2 {
		t.Fatalf("client-a after restart: %v", got)
	}
	if got := names(s2.jar("client-a", true).Cookies(other)); len(got) != 0 {
		t.Fatalf("path /app must not match /application: %v", got)
	}
	if got := names(s2.jar("client-b", true).Cookies(page)); got["auth"] != "other" || len(got) != 1 {
		t.Fatalf("client-b isolation: %v", got)
	}

	// Logout (MaxAge<0) removes the cookie, and that persists too.
	s2.jar("client-a", true).SetCookies(u, []*http.Cookie{{Name: "auth", Path: "/app", MaxAge: -1}})
	s2.Flush()
	s3 := newCookieStore(path)
	if got := names(s3.jar("client-a", true).Cookies(page)); got["auth"] != "" || got["pref"] != "1" {
		t.Fatalf("after logout: %v", got)
	}
}

func TestCookieStoreMemoryOnly(t *testing.T) {
	s := newCookieStore("")
	u, _ := url.Parse("http://127.0.0.1:1/a/b")
	s.jar("c", true).SetCookies(u, []*http.Cookie{{Name: "n", Value: "v"}}) // default path "/a"
	if got := names(s.jar("c", true).Cookies(u)); got["n"] != "v" {
		t.Fatalf("got %v", got)
	}
	s.Flush() // no-op, must not panic
}

// FileBrowser Quantum's real login and logout headers: logging out must
// delete the login cookie from the visitor's jar.
func TestLogoutDeletesCookie(t *testing.T) {
	s := newCookieStore("")
	u, _ := url.Parse("http://127.0.0.1:1/p2p_fileserver/fs/api/auth/login")
	parse := func(h string) []*http.Cookie {
		return (&http.Response{Header: http.Header{"Set-Cookie": {h}}}).Cookies()
	}
	exp := time.Now().Add(30 * time.Minute).UTC().Format(http.TimeFormat)
	s.jar("c", false).SetCookies(u, parse("filebrowser_quantum_jwt=tok; Path=/; Domain=127.0.0.1; Expires="+exp+"; HttpOnly; SameSite=Strict"))
	if got := names(s.jar("c", false).Cookies(u)); got["filebrowser_quantum_jwt"] != "tok" {
		t.Fatalf("after login: %v", got)
	}
	out, _ := url.Parse("http://127.0.0.1:1/p2p_fileserver/fs/api/auth/logout")
	s.jar("c", false).SetCookies(out, parse("filebrowser_quantum_jwt=; Path=/; Domain=127.0.0.1; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; HttpOnly; SameSite=Strict"))
	if got := names(s.jar("c", false).Cookies(u)); len(got) != 0 {
		t.Fatalf("after logout: %v", got)
	}
}

// Browsers that didn't ask to be remembered lose their cookies after a short
// idle time; remembered ones keep them.
func TestEphemeralJarExpires(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cookies.json")
	s := newCookieStore(path)
	u, _ := url.Parse("http://127.0.0.1:1/a/")
	s.jar("tab", false).SetCookies(u, []*http.Cookie{{Name: "auth", Value: "1", Path: "/"}})
	s.jar("home", true).SetCookies(u, []*http.Cookie{{Name: "auth", Value: "2", Path: "/"}})
	idle := time.Now().Add(-ephemeralIdleTTL - time.Minute)
	s.mu.Lock()
	s.clients["tab"].LastUsed = idle
	s.clients["home"].LastUsed = idle
	s.mu.Unlock()
	s.Flush()

	s2 := newCookieStore(path)
	if got := names(s2.jar("tab", false).Cookies(u)); len(got) != 0 {
		t.Fatalf("ephemeral jar survived its TTL: %v", got)
	}
	if got := names(s2.jar("home", true).Cookies(u)); got["auth"] != "2" {
		t.Fatalf("remembered jar lost: %v", got)
	}
}
