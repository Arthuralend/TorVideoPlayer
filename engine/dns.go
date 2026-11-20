package main

import (
	"context"
	"flag"
	"log"
	"net"
	"os"
	"runtime"
	"strings"
	"sync/atomic"
	"time"
)

var dnsFlag = flag.String("dns", "", "comma-separated DNS servers of the device (used when the system resolver does not work)")

// Public resolvers tried after the device's own servers.
var publicDNS = []string{"8.8.8.8", "1.1.1.1", "77.88.8.8", "9.9.9.9", "8.8.4.4", "1.0.0.1"}

// setupDNS makes sure host names (trackers, DHT bootstrap nodes) resolve.
//
// Android has no /etc/resolv.conf. Engines built with the NDK use the
// system resolver; it is checked once and, like engines built without cgo,
// replaced by a Go resolver that asks the device's DNS servers (passed by
// the app) and then public ones, a different server on every attempt.
func setupDNS() {
	servers := dnsServers()
	switch {
	case runtime.GOOS == "android" && haveCgo:
		if systemResolverWorks() {
			log.Printf("dns: system resolver")
			return
		}
		log.Printf("dns: system resolver failed, using %v", servers)
	case fileExists("/etc/resolv.conf"):
		return
	default:
		log.Printf("dns: no resolv.conf, using %v", servers)
	}
	var next uint32
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 3 * time.Second}
			start := int(atomic.AddUint32(&next, 1) - 1)
			var lastErr error
			for i := range servers {
				conn, err := d.DialContext(ctx, network, servers[(start+i)%len(servers)])
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}

func dnsServers() []string {
	var list []string
	seen := map[string]bool{}
	add := func(s string) {
		s = strings.TrimSpace(strings.Trim(strings.TrimSpace(s), "[]"))
		if s == "" || seen[s] {
			return
		}
		seen[s] = true
		list = append(list, net.JoinHostPort(s, "53"))
	}
	for _, s := range strings.Split(*dnsFlag, ",") {
		add(s)
	}
	for _, s := range publicDNS {
		add(s)
	}
	return list
}

func systemResolverWorks() bool {
	for _, host := range []string{"router.bittorrent.com", "dht.transmissionbt.com", "google.com"} {
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
		addrs, err := net.DefaultResolver.LookupHost(ctx, host)
		cancel()
		if err == nil && len(addrs) > 0 {
			return true
		}
		log.Printf("dns: system lookup %s: %v", host, err)
	}
	return false
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
