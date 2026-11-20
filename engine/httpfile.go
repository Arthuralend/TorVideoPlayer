package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// httpFile reads a file from a local HTTP server that supports Range
// requests, in chunks, so the MKV parser can seek through it.
type httpFile struct {
	ctx      context.Context
	url      string
	size     int64
	pos      int64
	buf      []byte
	bufStart int64
}

const httpFileChunk = 512 << 10

func openHTTPFile(ctx context.Context, raw string) (*httpFile, error) {
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		return nil, errors.New("bad url")
	}
	// Only local servers: this API must not become an open proxy.
	if h := u.Hostname(); h != "127.0.0.1" && h != "localhost" && h != "::1" {
		return nil, errors.New("only local urls are allowed")
	}
	f := &httpFile{ctx: ctx, url: raw, bufStart: -1}
	data, total, err := f.fetch(0, 0)
	if err != nil {
		return nil, err
	}
	if total < 0 {
		return nil, errors.New("server does not report the file size")
	}
	_ = data
	f.size = total
	return f, nil
}

// fetch reads bytes [from, to] and returns them with the total file size
// taken from Content-Range (-1 if unknown).
func (f *httpFile) fetch(from, to int64) ([]byte, int64, error) {
	req, err := http.NewRequestWithContext(f.ctx, http.MethodGet, f.url, nil)
	if err != nil {
		return nil, -1, err
	}
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", from, to))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, -1, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusPartialContent {
		return nil, -1, fmt.Errorf("range request: HTTP %d", resp.StatusCode)
	}
	total := int64(-1)
	if cr := resp.Header.Get("Content-Range"); cr != "" {
		if i := strings.LastIndexByte(cr, '/'); i >= 0 {
			if n, err := strconv.ParseInt(cr[i+1:], 10, 64); err == nil {
				total = n
			}
		}
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, to-from+1))
	return data, total, err
}

func (f *httpFile) Read(p []byte) (int, error) {
	if f.pos >= f.size {
		return 0, io.EOF
	}
	if f.bufStart < 0 || f.pos < f.bufStart || f.pos >= f.bufStart+int64(len(f.buf)) {
		end := f.pos + httpFileChunk - 1
		if end >= f.size {
			end = f.size - 1
		}
		data, _, err := f.fetch(f.pos, end)
		if err != nil {
			return 0, err
		}
		if len(data) == 0 {
			return 0, io.ErrUnexpectedEOF
		}
		f.buf, f.bufStart = data, f.pos
	}
	n := copy(p, f.buf[f.pos-f.bufStart:])
	f.pos += int64(n)
	return n, nil
}

func (f *httpFile) Seek(offset int64, whence int) (int64, error) {
	var abs int64
	switch whence {
	case io.SeekStart:
		abs = offset
	case io.SeekCurrent:
		abs = f.pos + offset
	case io.SeekEnd:
		abs = f.size + offset
	default:
		return 0, errors.New("bad whence")
	}
	if abs < 0 {
		return 0, errors.New("negative position")
	}
	f.pos = abs
	return abs, nil
}

func (f *httpFile) Close() error { return nil }
