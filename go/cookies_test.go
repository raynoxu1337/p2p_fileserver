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
	s1.jar("client-a").SetCookies(u, []*http.Cookie{
		{Name: "auth", Value: "tok", Path: "/app", HttpOnly: true}, // session cookie
		{Name: "pref", Value: "1", Path: "/app/", MaxAge: 3600},
		{Name: "old", Value: "x", Path: "/app", Expires: time.Now().Add(-time.Hour)},
	})
	s1.jar("client-b").SetCookies(u, []*http.Cookie{{Name: "auth", Value: "other", Path: "/"}})
	s1.Flush()

	s2 := newCookieStore(path) // "restart"
	got := names(s2.jar("client-a").Cookies(page))
	if got["auth"] != "tok" || got["pref"] != "1" || len(got) != 2 {
		t.Fatalf("client-a after restart: %v", got)
	}
	if got := names(s2.jar("client-a").Cookies(other)); len(got) != 0 {
		t.Fatalf("path /app must not match /application: %v", got)
	}
	if got := names(s2.jar("client-b").Cookies(page)); got["auth"] != "other" || len(got) != 1 {
		t.Fatalf("client-b isolation: %v", got)
	}

	// Logout (MaxAge<0) removes the cookie, and that persists too.
	s2.jar("client-a").SetCookies(u, []*http.Cookie{{Name: "auth", Path: "/app", MaxAge: -1}})
	s2.Flush()
	s3 := newCookieStore(path)
	if got := names(s3.jar("client-a").Cookies(page)); got["auth"] != "" || got["pref"] != "1" {
		t.Fatalf("after logout: %v", got)
	}
}

func TestCookieStoreMemoryOnly(t *testing.T) {
	s := newCookieStore("")
	u, _ := url.Parse("http://127.0.0.1:1/a/b")
	s.jar("c").SetCookies(u, []*http.Cookie{{Name: "n", Value: "v"}}) // default path "/a"
	if got := names(s.jar("c").Cookies(u)); got["n"] != "v" {
		t.Fatalf("got %v", got)
	}
	s.Flush() // no-op, must not panic
}
