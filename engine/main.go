// torengine — a tiny local BitTorrent streaming server used by TorVideoPlayer.
//
// It adds torrents (magnet links, .torrent files or URLs), lets the player
// stream any file over HTTP with Range support while it is still downloading
// (pieces under the playback position are prioritised), and reports
// download statistics. The process exits when its stdin is closed, so it
// never outlives the app that started it.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/metainfo"
	"github.com/anacrolix/torrent/storage"
)

var videoExt = map[string]bool{
	".mkv": true, ".mp4": true, ".m4v": true, ".avi": true, ".mov": true, ".wmv": true,
	".webm": true, ".ts": true, ".m2ts": true, ".mts": true, ".mpg": true, ".mpeg": true,
	".flv": true, ".ogv": true, ".3gp": true, ".vob": true, ".divx": true, ".rmvb": true,
}

var audioExt = map[string]bool{
	".mp3": true, ".flac": true, ".m4a": true, ".aac": true, ".ogg": true, ".opus": true,
	".wav": true, ".ac3": true, ".dts": true, ".mka": true, ".eac3": true,
}

var subtitleExt = map[string]bool{
	".srt": true, ".ass": true, ".ssa": true, ".vtt": true, ".sub": true, ".idx": true,
}

type fileJSON struct {
	Index      int    `json:"index"`
	Path       string `json:"path"`
	Length     int64  `json:"length"`
	Kind       string `json:"kind"` // video | audio | subtitle | other
	StreamPath string `json:"streamPath"`
}

type torrentJSON struct {
	InfoHash string     `json:"infoHash"`
	Name     string     `json:"name"`
	Length   int64      `json:"length"`
	Files    []fileJSON `json:"files"`
}

type statusJSON struct {
	InfoHash       string  `json:"infoHash"`
	Peers          int     `json:"peers"`
	ActivePeers    int     `json:"activePeers"`
	Seeders        int     `json:"seeders"`
	DownloadSpeed  float64 `json:"downloadSpeed"` // bytes per second
	UploadSpeed    float64 `json:"uploadSpeed"`
	BytesCompleted int64   `json:"bytesCompleted"`
	Length         int64   `json:"length"`
	FileCompleted  int64   `json:"fileCompleted,omitempty"`
	FileLength     int64   `json:"fileLength,omitempty"`
}

type speedSample struct {
	at       time.Time
	read     int64
	written  int64
	down, up float64
}

type server struct {
	client  *torrent.Client
	mu      sync.Mutex
	samples map[metainfo.Hash]*speedSample
	mkv     map[string]*mkvEntry
}

func main() {
	var (
		dataDir    = flag.String("data", filepath.Join(os.TempDir(), "TorVideoPlayer"), "download directory")
		httpAddr   = flag.String("http", "127.0.0.1:0", "HTTP listen address")
		listenPort = flag.Int("port", 0, "BitTorrent listen port (0 = random)")
		cleanup    = flag.Bool("cleanup", true, "delete downloaded data on exit")
		watchStdin = flag.Bool("watch-stdin", true, "exit when stdin is closed")
	)
	flag.Parse()
	log.SetFlags(log.Ltime)
	log.SetOutput(os.Stderr)

	if err := os.MkdirAll(*dataDir, 0o755); err != nil {
		log.Fatalf("data dir: %v", err)
	}

	cfg := torrent.NewDefaultClientConfig()
	cfg.DataDir = *dataDir
	cfg.ListenPort = *listenPort
	cfg.Seed = true
	cfg.NoUpload = false
	cfg.DefaultStorage = storage.NewFileOpts(storage.NewFileClientOpts{
		ClientBaseDir:   *dataDir,
		PieceCompletion: storage.NewMapPieceCompletion(),
	})
	cfg.EstablishedConnsPerTorrent = 80
	cfg.HalfOpenConnsPerTorrent = 40
	cfg.TorrentPeersHighWater = 1000

	client, err := torrent.NewClient(cfg)
	if err != nil {
		// Some networks/hosts have no IPv6; retry without it.
		log.Printf("torrent client: %v; retrying without IPv6", err)
		cfg.DisableIPv6 = true
		if client, err = torrent.NewClient(cfg); err != nil {
			log.Fatalf("torrent client: %v", err)
		}
	}

	s := &server{client: client, samples: map[metainfo.Hash]*speedSample{}, mkv: map[string]*mkvEntry{}}
	go s.sampleSpeeds()

	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "ok") })
	mux.HandleFunc("/api/add", s.handleAdd)
	mux.HandleFunc("/api/torrents", s.handleList)
	mux.HandleFunc("/api/status/", s.handleStatus)
	mux.HandleFunc("/api/drop/", s.handleDrop)
	mux.HandleFunc("/stream/", s.handleStream)
	mux.HandleFunc("/api/mkv/tracks", s.handleMKVTracks)
	mux.HandleFunc("/api/mkv/cues", s.handleMKVCues)

	ln, err := net.Listen("tcp", *httpAddr)
	if err != nil {
		log.Fatalf("listen: %v", err)
	}
	// The app reads this line to learn the port.
	fmt.Printf("LISTENING %d\n", ln.Addr().(*net.TCPAddr).Port)
	os.Stdout.Sync()

	httpSrv := &http.Server{Handler: mux}
	go func() {
		if err := httpSrv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("http: %v", err)
		}
	}()

	done := make(chan struct{})
	var once sync.Once
	stop := func() { once.Do(func() { close(done) }) }

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	go func() { <-sig; stop() }()

	if *watchStdin {
		go func() {
			io.Copy(io.Discard, os.Stdin)
			stop()
		}()
	}

	<-done
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	httpSrv.Shutdown(ctx)
	cancel()
	client.Close()
	if *cleanup {
		os.RemoveAll(*dataDir)
	}
}

func fileKind(p string) string {
	ext := strings.ToLower(path.Ext(p))
	switch {
	case videoExt[ext]:
		return "video"
	case audioExt[ext]:
		return "audio"
	case subtitleExt[ext]:
		return "subtitle"
	}
	return "other"
}

func describe(t *torrent.Torrent) torrentJSON {
	ih := t.InfoHash().HexString()
	res := torrentJSON{InfoHash: ih, Name: t.Name(), Length: t.Length()}
	for i, f := range t.Files() {
		p := f.DisplayPath()
		res.Files = append(res.Files, fileJSON{
			Index:      i,
			Path:       p,
			Length:     f.Length(),
			Kind:       fileKind(p),
			StreamPath: fmt.Sprintf("/stream/%s/%d/%s", ih, i, url.PathEscape(path.Base(p))),
		})
	}
	return res
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, err error) {
	writeJSON(w, code, map[string]string{"error": err.Error()})
}

// handleAdd accepts either a raw .torrent body (Content-Type
// application/x-bittorrent) or JSON {"uri": "..."} where uri is a magnet
// link, a bare info hash, an http(s) URL of a .torrent file or a local path.
// It waits until the torrent metadata is known and returns the file list.
func (s *server) handleAdd(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, errors.New("POST only"))
		return
	}
	var (
		t   *torrent.Torrent
		err error
	)
	if strings.HasPrefix(r.Header.Get("Content-Type"), "application/x-bittorrent") {
		t, err = s.addTorrentReader(io.LimitReader(r.Body, 64<<20))
	} else {
		var req struct {
			URI string `json:"uri"`
		}
		if err = json.NewDecoder(r.Body).Decode(&req); err == nil {
			t, err = s.addURI(r.Context(), strings.TrimSpace(req.URI))
		}
	}
	if err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}

	timeout := 3 * time.Minute
	if v, e := strconv.Atoi(r.URL.Query().Get("timeout")); e == nil && v > 0 {
		timeout = time.Duration(v) * time.Second
	}
	select {
	case <-t.GotInfo():
	case <-time.After(timeout):
		writeErr(w, http.StatusGatewayTimeout, errors.New("не удалось получить метаданные торрента (нет пиров?)"))
		return
	case <-r.Context().Done():
		return
	}
	writeJSON(w, http.StatusOK, describe(t))
}

func (s *server) addTorrentReader(rd io.Reader) (*torrent.Torrent, error) {
	mi, err := metainfo.Load(rd)
	if err != nil {
		return nil, fmt.Errorf("bad .torrent: %w", err)
	}
	spec, err := torrent.TorrentSpecFromMetaInfoErr(mi)
	if err != nil {
		return nil, err
	}
	t, _, err := s.client.AddTorrentSpec(spec)
	return t, err
}

func (s *server) addURI(ctx context.Context, uri string) (*torrent.Torrent, error) {
	lower := strings.ToLower(uri)
	switch {
	case uri == "":
		return nil, errors.New("пустая ссылка")
	case strings.HasPrefix(lower, "magnet:"):
		return s.client.AddMagnet(uri)
	case len(uri) == 40 && isHex(uri):
		return s.client.AddMagnet("magnet:?xt=urn:btih:" + uri)
	case strings.HasPrefix(lower, "http://"), strings.HasPrefix(lower, "https://"):
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, uri, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("User-Agent", "TorVideoPlayer/1.0")
		// Some trackers redirect a download link to a magnet URI.
		cl := &http.Client{
			Timeout: 60 * time.Second,
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				if req.URL.Scheme == "magnet" {
					return http.ErrUseLastResponse
				}
				return nil
			},
		}
		resp, err := cl.Do(req)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		if loc := resp.Header.Get("Location"); strings.HasPrefix(strings.ToLower(loc), "magnet:") {
			return s.client.AddMagnet(loc)
		}
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("загрузка .torrent: HTTP %d", resp.StatusCode)
		}
		return s.addTorrentReader(io.LimitReader(resp.Body, 64<<20))
	default:
		p := strings.TrimPrefix(uri, "file://")
		if u, err := url.PathUnescape(p); err == nil {
			p = u
		}
		f, err := os.Open(p)
		if err != nil {
			return nil, fmt.Errorf("не похоже на magnet-ссылку, URL или .torrent файл: %w", err)
		}
		defer f.Close()
		return s.addTorrentReader(f)
	}
}

func isHex(s string) bool {
	for _, c := range s {
		if !strings.ContainsRune("0123456789abcdefABCDEF", c) {
			return false
		}
	}
	return true
}

func (s *server) torrentFromPath(p string) (*torrent.Torrent, []string, error) {
	parts := strings.Split(strings.Trim(p, "/"), "/")
	if len(parts) == 0 || parts[0] == "" {
		return nil, nil, errors.New("missing info hash")
	}
	var h metainfo.Hash
	if err := h.FromHexString(parts[0]); err != nil {
		return nil, nil, err
	}
	t, ok := s.client.Torrent(h)
	if !ok {
		return nil, nil, errors.New("torrent not found")
	}
	return t, parts[1:], nil
}

func (s *server) handleList(w http.ResponseWriter, r *http.Request) {
	res := []torrentJSON{}
	for _, t := range s.client.Torrents() {
		if t.Info() != nil {
			res = append(res, describe(t))
		}
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *server) handleStatus(w http.ResponseWriter, r *http.Request) {
	t, _, err := s.torrentFromPath(strings.TrimPrefix(r.URL.Path, "/api/status/"))
	if err != nil {
		writeErr(w, http.StatusNotFound, err)
		return
	}
	st := t.Stats()
	res := statusJSON{
		InfoHash:    t.InfoHash().HexString(),
		Peers:       st.TotalPeers,
		ActivePeers: st.ActivePeers,
		Seeders:     st.ConnectedSeeders,
	}
	if t.Info() != nil {
		res.BytesCompleted = t.BytesCompleted()
		res.Length = t.Length()
		if v, e := strconv.Atoi(r.URL.Query().Get("file")); e == nil && v >= 0 && v < len(t.Files()) {
			f := t.Files()[v]
			res.FileCompleted = f.BytesCompleted()
			res.FileLength = f.Length()
		}
	}
	s.mu.Lock()
	if sm := s.samples[t.InfoHash()]; sm != nil {
		res.DownloadSpeed, res.UploadSpeed = sm.down, sm.up
	}
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, res)
}

func (s *server) handleDrop(w http.ResponseWriter, r *http.Request) {
	t, _, err := s.torrentFromPath(strings.TrimPrefix(r.URL.Path, "/api/drop/"))
	if err != nil {
		writeErr(w, http.StatusNotFound, err)
		return
	}
	t.Drop()
	s.mu.Lock()
	delete(s.samples, t.InfoHash())
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// handleStream serves /stream/<infohash>/<fileIndex>[/<name>] with Range
// support. The torrent reader prioritises pieces around the read position,
// so seeking in the player immediately re-targets the download.
func (s *server) handleStream(w http.ResponseWriter, r *http.Request) {
	t, rest, err := s.torrentFromPath(strings.TrimPrefix(r.URL.Path, "/stream/"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	select {
	case <-t.GotInfo():
	case <-r.Context().Done():
		return
	}
	if len(rest) == 0 {
		http.Error(w, "missing file index", http.StatusBadRequest)
		return
	}
	idx, err := strconv.Atoi(rest[0])
	files := t.Files()
	if err != nil || idx < 0 || idx >= len(files) {
		http.Error(w, "bad file index", http.StatusBadRequest)
		return
	}
	f := files[idx]
	f.SetPriority(torrent.PiecePriorityNormal)

	rd := f.NewReader()
	defer rd.Close()
	rd.SetContext(r.Context())
	rd.SetResponsive()
	rd.SetReadahead(readaheadFor(f.Length()))

	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Cache-Control", "no-store")
	if fileKind(f.DisplayPath()) == "subtitle" {
		w.Header().Set("Content-Type", "application/octet-stream")
	}
	http.ServeContent(w, r, path.Base(f.DisplayPath()), time.Time{}, rd)
}

// readaheadFor picks how far ahead of the playback position to download:
// roughly 1% of the file, clamped to 8–64 MiB.
func readaheadFor(length int64) int64 {
	ra := length / 100
	if ra < 8<<20 {
		ra = 8 << 20
	}
	if ra > 64<<20 {
		ra = 64 << 20
	}
	return ra
}

func (s *server) sampleSpeeds() {
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for now := range tick.C {
		for _, t := range s.client.Torrents() {
			st := t.Stats()
			read := st.BytesReadUsefulData.Int64()
			written := st.BytesWrittenData.Int64()
			s.mu.Lock()
			sm := s.samples[t.InfoHash()]
			if sm == nil {
				sm = &speedSample{at: now, read: read, written: written}
				s.samples[t.InfoHash()] = sm
			} else if dt := now.Sub(sm.at).Seconds(); dt > 0 {
				// Exponential smoothing so the numbers don't jump around.
				down := float64(read-sm.read) / dt
				up := float64(written-sm.written) / dt
				sm.down = 0.6*sm.down + 0.4*down
				sm.up = 0.6*sm.up + 0.4*up
				sm.at, sm.read, sm.written = now, read, written
			}
			s.mu.Unlock()
		}
	}
}
