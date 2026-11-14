package main

import (
	"context"
	"net"
	"os"
	"time"
)

// On Android there is no /etc/resolv.conf, so Go's built-in resolver would
// try 127.0.0.1:53 and fail. Fall back to public DNS servers in that case.
func init() {
	if _, err := os.Stat("/etc/resolv.conf"); err == nil {
		return
	}
	servers := []string{"8.8.8.8:53", "1.1.1.1:53", "77.88.8.8:53", "9.9.9.9:53"}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 3 * time.Second}
			var lastErr error
			for _, s := range servers {
				conn, err := d.DialContext(ctx, network, s)
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}
