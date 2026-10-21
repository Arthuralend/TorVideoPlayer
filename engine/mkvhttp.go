package main

import (
	"errors"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"

	"github.com/anacrolix/torrent/metainfo"
)

// mkvEntry caches the parsed header of one file; mu serialises readers.
type mkvEntry struct {
	mu   sync.Mutex
	info *mkvInfo
	err  error
	done bool
}

type readSeekCloser interface {
	io.ReadSeeker
	io.Closer
}

// openMKVSource opens either a torrent file (?ih=&file=) or a local file
// (?path=) for reading.
func (s *server) openMKVSource(r *http.Request) (string, readSeekCloser, int64, error) {
	q := r.URL.Query()
	if p := q.Get("path"); p != "" {
		f, err := os.Open(p)
		if err != nil {
			return "", nil, 0, err
		}
		st, err := f.Stat()
		if err != nil {
			f.Close()
			return "", nil, 0, err
		}
		return "path:" + p, f, st.Size(), nil
	}
	var h metainfo.Hash
	if err := h.FromHexString(q.Get("ih")); err != nil {
		return "", nil, 0, errors.New("bad info hash")
	}
	t, ok := s.client.Torrent(h)
	if !ok {
		return "", nil, 0, errors.New("torrent not found")
	}
	select {
	case <-t.GotInfo():
	case <-r.Context().Done():
		return "", nil, 0, r.Context().Err()
	}
	idx, err := strconv.Atoi(q.Get("file"))
	files := t.Files()
	if err != nil || idx < 0 || idx >= len(files) {
		return "", nil, 0, errors.New("bad file index")
	}
	f := files[idx]
	rd := f.NewReader()
	rd.SetContext(r.Context())
	rd.SetResponsive()
	rd.SetReadahead(1 << 20)
	return "t:" + h.HexString() + "/" + strconv.Itoa(idx), rd, f.Length(), nil
}

// mkvFor returns the cached header for the request's file, parsing it on
// first use. The returned entry is locked; the caller must unlock it.
func (s *server) mkvFor(r *http.Request) (*mkvEntry, readSeekCloser, error) {
	key, rs, size, err := s.openMKVSource(r)
	if err != nil {
		return nil, nil, err
	}
	s.mu.Lock()
	e := s.mkv[key]
	if e == nil {
		e = &mkvEntry{}
		s.mkv[key] = e
	}
	s.mu.Unlock()

	e.mu.Lock()
	if !e.done {
		e.info, e.err = parseMKV(rs, size)
		// A cancelled request must not poison the cache.
		if r.Context().Err() == nil {
			e.done = true
		}
	}
	if e.err != nil {
		err := e.err
		e.mu.Unlock()
		rs.Close()
		return nil, nil, err
	}
	return e, rs, nil
}

func (s *server) handleMKVTracks(w http.ResponseWriter, r *http.Request) {
	e, rs, err := s.mkvFor(r)
	if err != nil {
		if errors.Is(err, errNotMKV) {
			writeJSON(w, http.StatusOK, map[string]any{"tracks": []mkvTrack{}, "mkv": false})
			return
		}
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	defer rs.Close()
	defer e.mu.Unlock()
	tracks := make([]mkvTrack, 0, len(e.info.tracks))
	for _, t := range e.info.tracks {
		tracks = append(tracks, *t)
	}
	writeJSON(w, http.StatusOK, map[string]any{"tracks": tracks, "mkv": true, "duration": e.info.duration})
}

// handleMKVCues: ?tracks=3,4&from=120&to=180 → {"cues": {"3": [...], "4": [...]}}
func (s *server) handleMKVCues(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	from, err1 := strconv.ParseFloat(q.Get("from"), 64)
	to, err2 := strconv.ParseFloat(q.Get("to"), 64)
	if err1 != nil || err2 != nil || to < from {
		writeErr(w, http.StatusBadRequest, errors.New("bad from/to"))
		return
	}
	var tracks []uint64
	for _, part := range strings.Split(q.Get("tracks"), ",") {
		if n, err := strconv.ParseUint(strings.TrimSpace(part), 10, 64); err == nil {
			tracks = append(tracks, n)
		}
	}
	e, rs, err := s.mkvFor(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	defer rs.Close()
	defer e.mu.Unlock()
	cues, err := e.info.extractCues(rs, tracks, from, to)
	if err != nil && r.Context().Err() != nil {
		return
	}
	out := map[string][]mkvCue{}
	for n, list := range cues {
		out[strconv.FormatUint(n, 10)] = list
	}
	writeJSON(w, http.StatusOK, map[string]any{"cues": out, "from": from, "to": to})
}
