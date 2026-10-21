package main

// A minimal Matroska (MKV/WebM) reader that extracts text subtitle tracks
// around a given time without reading the whole file. The player renders
// these itself, so any two embedded tracks can be shown at once (libVLC 3
// draws only one subtitle track).

import (
	"bufio"
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
	"sort"
	"strings"
)

const (
	idEBML          = 0x1A45DFA3
	idSegment       = 0x18538067
	idSeekHead      = 0x114D9B74
	idSeek          = 0x4DBB
	idSeekID        = 0x53AB
	idSeekPosition  = 0x53AC
	idInfo          = 0x1549A966
	idTimecodeScale = 0x2AD7B1
	idDuration      = 0x4489
	idTracks        = 0x1654AE6B
	idTrackEntry    = 0xAE
	idTrackNumber   = 0xD7
	idTrackType     = 0x83
	idCodecID       = 0x86
	idLanguage      = 0x22B59C
	idLanguageIETF  = 0x22B59D
	idName          = 0x536E
	idFlagDefault   = 0x88
	idFlagForced    = 0x55AA
	idContentEncs   = 0x6D80
	idContentEnc    = 0x6240
	idContentComp   = 0x5034
	idCompAlgo      = 0x4254
	idCompSettings  = 0x4255
	idCues          = 0x1C53BB6B
	idCuePoint      = 0xBB
	idCueTime       = 0xB3
	idCueTrackPos   = 0xB7
	idCueClusterPos = 0xF1
	idCluster       = 0x1F43B675
	idTimestamp     = 0xE7
	idSimpleBlock   = 0xA3
	idBlockGroup    = 0xA0
	idBlock         = 0xA1
	idBlockDuration = 0x9B
	idVoid          = 0xEC
	idCRC32         = 0xBF
	idTags          = 0x1254C367
	idChapters      = 0x1043A770
	idAttachments   = 0x1941A469

	unknownSize = -1
)

var errNotMKV = errors.New("not a Matroska file")

type mkvTrack struct {
	Number   uint64 `json:"number"`
	Codec    string `json:"codec"`
	Language string `json:"language"`
	Name     string `json:"name"`
	Default  bool   `json:"default"`
	Forced   bool   `json:"forced"`
	Text     bool   `json:"text"` // can be extracted as text

	compAlgo     int // -1 none, 0 zlib, 3 header stripping
	compSettings []byte
}

type mkvCue struct {
	Start float64 `json:"start"`
	End   float64 `json:"end"`
	Text  string  `json:"text"`
}

type cuePoint struct {
	time float64 // seconds
	pos  int64   // absolute file offset of the cluster
}

// mkvInfo is the parsed header of a file; it is cached and reused with
// fresh readers for every request.
type mkvInfo struct {
	size          int64
	segStart      int64
	segEnd        int64
	timecodeScale float64 // ns per tick
	duration      float64 // seconds
	firstCluster  int64
	cuesPos       int64
	cues          []cuePoint
	cuesLoaded    bool
	tracks        []*mkvTrack
}

// ebmlReader wraps a ReadSeeker and tracks the absolute position.
type ebmlReader struct {
	rs  io.ReadSeeker
	br  *bufio.Reader
	pos int64
	// Start of the element most recently passed to a children() callback;
	// valid at the beginning of the callback.
	elemStart int64
}

func newEBMLReader(rs io.ReadSeeker, pos int64) (*ebmlReader, error) {
	if _, err := rs.Seek(pos, io.SeekStart); err != nil {
		return nil, err
	}
	return &ebmlReader{rs: rs, br: bufio.NewReaderSize(rs, 64<<10), pos: pos}, nil
}

func (r *ebmlReader) seek(pos int64) error {
	// Skip forward within the buffer when possible.
	if d := pos - r.pos; d >= 0 && d <= int64(r.br.Buffered()) {
		r.br.Discard(int(d))
		r.pos = pos
		return nil
	}
	if _, err := r.rs.Seek(pos, io.SeekStart); err != nil {
		return err
	}
	r.br.Reset(r.rs)
	r.pos = pos
	return nil
}

func (r *ebmlReader) readByte() (byte, error) {
	b, err := r.br.ReadByte()
	if err == nil {
		r.pos++
	}
	return b, err
}

func (r *ebmlReader) readFull(n int64) ([]byte, error) {
	if n < 0 || n > 64<<20 {
		return nil, fmt.Errorf("element too large: %d", n)
	}
	buf := make([]byte, n)
	m, err := io.ReadFull(r.br, buf)
	r.pos += int64(m)
	return buf, err
}

// readVint reads an EBML variable-length integer. With keepMarker it
// returns element IDs as they are written (marker bit included).
func (r *ebmlReader) readVint(keepMarker bool) (uint64, int, error) {
	first, err := r.readByte()
	if err != nil {
		return 0, 0, err
	}
	length := 1
	mask := byte(0x80)
	for length <= 8 && first&mask == 0 {
		mask >>= 1
		length++
	}
	if length > 8 {
		return 0, 0, errors.New("invalid vint")
	}
	value := uint64(first)
	if !keepMarker {
		value = uint64(first & (mask - 1))
	}
	allOnes := first&(mask-1) == mask-1
	for i := 1; i < length; i++ {
		b, err := r.readByte()
		if err != nil {
			return 0, 0, err
		}
		if b != 0xFF {
			allOnes = false
		}
		value = value<<8 | uint64(b)
	}
	if !keepMarker && allOnes {
		return 0, length, errUnknownSize
	}
	return value, length, nil
}

var errUnknownSize = errors.New("unknown size")

// readHeader reads an element ID and size; size is unknownSize for
// elements of unknown length (live-style Segments/Clusters).
func (r *ebmlReader) readHeader() (id uint64, size int64, err error) {
	id, _, err = r.readVint(true)
	if err != nil {
		return 0, 0, err
	}
	s, _, err := r.readVint(false)
	if errors.Is(err, errUnknownSize) {
		return id, unknownSize, nil
	}
	if err != nil {
		return 0, 0, err
	}
	return id, int64(s), nil
}

func readUint(b []byte) uint64 {
	var v uint64
	for _, c := range b {
		v = v<<8 | uint64(c)
	}
	return v
}

func readFloat(b []byte) float64 {
	switch len(b) {
	case 4:
		return float64(math.Float32frombits(binary.BigEndian.Uint32(b)))
	case 8:
		return math.Float64frombits(binary.BigEndian.Uint64(b))
	}
	return 0
}

// children iterates over the child elements of a master element spanning
// [start, end). fn returns false to stop.
func (r *ebmlReader) children(start, end int64, fn func(id uint64, size int64, dataPos int64) (bool, error)) error {
	pos := start
	for end < 0 || pos < end {
		if err := r.seek(pos); err != nil {
			return err
		}
		id, size, err := r.readHeader()
		if err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
				return nil
			}
			return err
		}
		dataPos := r.pos
		r.elemStart = pos
		cont, err := fn(id, size, dataPos)
		if err != nil || !cont {
			return err
		}
		if size == unknownSize {
			return nil
		}
		pos = dataPos + size
	}
	return nil
}

func parseMKV(rs io.ReadSeeker, size int64) (*mkvInfo, error) {
	r, err := newEBMLReader(rs, 0)
	if err != nil {
		return nil, err
	}
	id, hsize, err := r.readHeader()
	if err != nil || id != idEBML || hsize == unknownSize {
		return nil, errNotMKV
	}
	if err := r.seek(r.pos + hsize); err != nil {
		return nil, err
	}
	id, ssize, err := r.readHeader()
	if err != nil || id != idSegment {
		return nil, errNotMKV
	}
	info := &mkvInfo{size: size, segStart: r.pos, timecodeScale: 1e6, cuesPos: -1, firstCluster: -1}
	info.segEnd = size
	if ssize != unknownSize && r.pos+ssize < size {
		info.segEnd = r.pos + ssize
	}

	err = r.children(info.segStart, info.segEnd, func(id uint64, size int64, dataPos int64) (bool, error) {
		switch id {
		case idSeekHead:
			return true, info.parseSeekHead(r, dataPos, dataPos+size)
		case idInfo:
			return true, info.parseInfo(r, dataPos, dataPos+size)
		case idTracks:
			return true, info.parseTracks(r, dataPos, dataPos+size)
		case idCues:
			info.cuesPos = r.elemStart
			return true, nil
		case idCluster:
			info.firstCluster = r.elemStart
			return false, nil // headers end at the first cluster
		}
		return size != unknownSize, nil
	})
	if err != nil {
		return nil, err
	}
	if info.tracks == nil {
		return nil, errors.New("no tracks found")
	}
	return info, nil
}

func (info *mkvInfo) parseSeekHead(r *ebmlReader, start, end int64) error {
	return r.children(start, end, func(id uint64, size int64, dataPos int64) (bool, error) {
		if id != idSeek {
			return true, nil
		}
		var seekID uint64
		var seekPos int64 = -1
		err := r.children(dataPos, dataPos+size, func(id uint64, size int64, dataPos int64) (bool, error) {
			b, err := r.readFull(size)
			if err != nil {
				return false, err
			}
			switch id {
			case idSeekID:
				seekID = readUint(b)
			case idSeekPosition:
				seekPos = int64(readUint(b))
			}
			return true, nil
		})
		if err == nil && seekID == idCues && seekPos >= 0 {
			info.cuesPos = info.segStart + seekPos
		}
		return true, err
	})
}

func (info *mkvInfo) parseInfo(r *ebmlReader, start, end int64) error {
	return r.children(start, end, func(id uint64, size int64, dataPos int64) (bool, error) {
		switch id {
		case idTimecodeScale:
			b, err := r.readFull(size)
			if err != nil {
				return false, err
			}
			if v := readUint(b); v > 0 {
				info.timecodeScale = float64(v)
			}
		case idDuration:
			b, err := r.readFull(size)
			if err != nil {
				return false, err
			}
			info.duration = readFloat(b) * info.timecodeScale / 1e9
		}
		return true, nil
	})
}

func (info *mkvInfo) parseTracks(r *ebmlReader, start, end int64) error {
	return r.children(start, end, func(id uint64, size int64, dataPos int64) (bool, error) {
		if id != idTrackEntry {
			return true, nil
		}
		t := &mkvTrack{Language: "und", Default: true, compAlgo: -1}
		var trackType uint64
		err := r.children(dataPos, dataPos+size, func(id uint64, size int64, dataPos int64) (bool, error) {
			switch id {
			case idTrackNumber, idTrackType, idFlagDefault, idFlagForced, idCodecID, idLanguage, idLanguageIETF, idName:
				b, err := r.readFull(size)
				if err != nil {
					return false, err
				}
				switch id {
				case idTrackNumber:
					t.Number = readUint(b)
				case idTrackType:
					trackType = readUint(b)
				case idFlagDefault:
					t.Default = readUint(b) != 0
				case idFlagForced:
					t.Forced = readUint(b) != 0
				case idCodecID:
					t.Codec = strings.TrimRight(string(b), "\x00")
				case idLanguage:
					if t.Language == "und" || t.Language == "" {
						t.Language = strings.TrimRight(string(b), "\x00")
					}
				case idLanguageIETF:
					t.Language = strings.TrimRight(string(b), "\x00")
				case idName:
					t.Name = strings.TrimRight(string(b), "\x00")
				}
			case idContentEncs:
				return true, info.parseEncodings(r, t, dataPos, dataPos+size)
			}
			return true, nil
		})
		if err != nil {
			return false, err
		}
		if trackType == 0x11 {
			switch t.Codec {
			case "S_TEXT/UTF8", "S_TEXT/ASCII", "S_TEXT/ASS", "S_TEXT/SSA", "S_ASS", "S_SSA", "S_TEXT/WEBVTT":
				t.Text = t.compAlgo == -1 || t.compAlgo == 0 || t.compAlgo == 3
			}
			info.tracks = append(info.tracks, t)
		}
		if info.tracks == nil {
			info.tracks = []*mkvTrack{}
		}
		return true, nil
	})
}

func (info *mkvInfo) parseEncodings(r *ebmlReader, t *mkvTrack, start, end int64) error {
	return r.children(start, end, func(id uint64, size int64, dataPos int64) (bool, error) {
		if id != idContentEnc {
			return true, nil
		}
		return true, r.children(dataPos, dataPos+size, func(id uint64, size int64, dataPos int64) (bool, error) {
			if id != idContentComp {
				return true, nil
			}
			t.compAlgo = 0 // zlib unless stated otherwise
			return true, r.children(dataPos, dataPos+size, func(id uint64, size int64, dataPos int64) (bool, error) {
				b, err := r.readFull(size)
				if err != nil {
					return false, err
				}
				switch id {
				case idCompAlgo:
					t.compAlgo = int(readUint(b))
				case idCompSettings:
					t.compSettings = b
				}
				return true, nil
			})
		})
	})
}

func (info *mkvInfo) loadCues(rs io.ReadSeeker) {
	if info.cuesLoaded {
		return
	}
	info.cuesLoaded = true
	if info.cuesPos < 0 {
		return
	}
	r, err := newEBMLReader(rs, info.cuesPos)
	if err != nil {
		return
	}
	id, size, err := r.readHeader()
	if err != nil || id != idCues || size == unknownSize {
		return
	}
	var points []cuePoint
	r.children(r.pos, r.pos+size, func(id uint64, size int64, dataPos int64) (bool, error) {
		if id != idCuePoint {
			return true, nil
		}
		var t uint64
		pos := int64(-1)
		r.children(dataPos, dataPos+size, func(id uint64, size int64, dataPos int64) (bool, error) {
			switch id {
			case idCueTime:
				b, err := r.readFull(size)
				if err != nil {
					return false, err
				}
				t = readUint(b)
			case idCueTrackPos:
				r.children(dataPos, dataPos+size, func(id uint64, size int64, dataPos int64) (bool, error) {
					if id == idCueClusterPos {
						b, err := r.readFull(size)
						if err != nil {
							return false, err
						}
						if pos < 0 {
							pos = info.segStart + int64(readUint(b))
						}
					}
					return true, nil
				})
			}
			return true, nil
		})
		if pos >= 0 {
			points = append(points, cuePoint{time: float64(t) * info.timecodeScale / 1e9, pos: pos})
		}
		return true, nil
	})
	sort.Slice(points, func(i, j int) bool { return points[i].time < points[j].time })
	info.cues = points
}

func (info *mkvInfo) track(number uint64) *mkvTrack {
	for _, t := range info.tracks {
		if t.Number == number {
			return t
		}
	}
	return nil
}

// clusterBefore returns the position of a cluster that starts at or before
// `at` seconds.
func (info *mkvInfo) clusterBefore(rs io.ReadSeeker, at float64) int64 {
	info.loadCues(rs)
	if len(info.cues) > 0 {
		i := sort.Search(len(info.cues), func(i int) bool { return info.cues[i].time > at })
		if i == 0 {
			return info.firstCluster
		}
		return info.cues[i-1].pos
	}
	// No index: estimate from the bitrate and resync on the next cluster.
	if info.duration <= 0 || at <= 0 || info.firstCluster < 0 {
		return info.firstCluster
	}
	span := float64(info.segEnd - info.firstCluster)
	guess := info.firstCluster + int64(span*at/info.duration)
	if pos := findClusterFrom(rs, guess, info.segEnd); pos >= 0 {
		return pos
	}
	return info.firstCluster
}

var clusterIDBytes = []byte{0x1F, 0x43, 0xB6, 0x75}

func findClusterFrom(rs io.ReadSeeker, from, end int64) int64 {
	if _, err := rs.Seek(from, io.SeekStart); err != nil {
		return -1
	}
	buf := make([]byte, 1<<20)
	offset := from
	for scanned := 0; scanned < 64<<20 && offset < end; {
		n, err := io.ReadFull(rs, buf)
		if n > 0 {
			if i := bytes.Index(buf[:n], clusterIDBytes); i >= 0 {
				return offset + int64(i)
			}
			// Keep 3 bytes of overlap in case the ID straddles the boundary.
			step := n - 3
			if step <= 0 {
				return -1
			}
			offset += int64(step)
			scanned += step
			if _, err := rs.Seek(offset, io.SeekStart); err != nil {
				return -1
			}
		}
		if err != nil {
			return -1
		}
	}
	return -1
}

// extractCues returns subtitle cues of the given tracks that are visible in
// [from, to] seconds, reading clusters sequentially from the index entry
// before `from`. Video/audio payloads are skipped, not decoded.
func (info *mkvInfo) extractCues(rs io.ReadSeeker, tracks []uint64, from, to float64) (map[uint64][]mkvCue, error) {
	want := map[uint64]*mkvTrack{}
	for _, n := range tracks {
		if t := info.track(n); t != nil && t.Text {
			want[n] = t
		}
	}
	result := map[uint64][]mkvCue{}
	for n := range want {
		result[n] = []mkvCue{}
	}
	if len(want) == 0 {
		return result, nil
	}

	// Long subtitle lines may start well before `from`.
	start := info.clusterBefore(rs, from-8)
	if start < 0 {
		return result, nil
	}
	r, err := newEBMLReader(rs, start)
	if err != nil {
		return nil, err
	}
	scale := info.timecodeScale / 1e9
	pos := start
	const maxScan = 512 << 20
	for pos < info.segEnd && pos-start < maxScan {
		if err := r.seek(pos); err != nil {
			return result, err
		}
		id, size, err := r.readHeader()
		if err != nil {
			break
		}
		dataPos := r.pos
		if id != idCluster {
			if size == unknownSize {
				break
			}
			pos = dataPos + size
			continue
		}
		clusterEnd := info.segEnd
		if size != unknownSize {
			clusterEnd = dataPos + size
		}
		var clusterTime float64
		stop := false
		next := clusterEnd
		err = r.children(dataPos, clusterEnd, func(id uint64, size int64, childPos int64) (bool, error) {
			switch id {
			case idTimestamp:
				b, err := r.readFull(size)
				if err != nil {
					return false, err
				}
				clusterTime = float64(readUint(b)) * scale
				if clusterTime > to {
					stop = true
					return false, nil
				}
			case idSimpleBlock:
				cue, n, ok, err := info.readBlock(r, want, size, clusterTime, 0)
				if err != nil {
					return false, err
				}
				if ok {
					result[n] = append(result[n], cue)
				}
			case idBlockGroup:
				var duration float64
				var blockPos, blockSize int64 = -1, 0
				r.children(childPos, childPos+size, func(id uint64, size int64, p int64) (bool, error) {
					switch id {
					case idBlock:
						blockPos, blockSize = p, size
					case idBlockDuration:
						b, err := r.readFull(size)
						if err != nil {
							return false, err
						}
						duration = float64(readUint(b)) * scale
					}
					return true, nil
				})
				if blockPos >= 0 {
					if err := r.seek(blockPos); err != nil {
						return false, err
					}
					cue, n, ok, err := info.readBlock(r, want, blockSize, clusterTime, duration)
					if err != nil {
						return false, err
					}
					if ok {
						result[n] = append(result[n], cue)
					}
				}
			case idCluster:
				// An unknown-size cluster ends where the next one starts.
				next = r.elemStart
				return false, nil
			default:
				if size == unknownSize {
					return false, nil
				}
			}
			return true, nil
		})
		if err != nil {
			return result, err
		}
		if stop {
			break
		}
		if size == unknownSize {
			if next >= clusterEnd || next <= pos {
				break
			}
			pos = next
			continue
		}
		pos = clusterEnd
	}

	for n, cues := range result {
		filtered := cues[:0]
		for i, c := range cues {
			if c.End <= c.Start {
				// No duration: show until the next line, at most 5 s.
				c.End = c.Start + 5
				if i+1 < len(cues) && cues[i+1].Start < c.End {
					c.End = cues[i+1].Start
				}
			}
			if c.End >= from && c.Start <= to && c.Text != "" {
				filtered = append(filtered, c)
			}
		}
		result[n] = filtered
	}
	return result, nil
}

// readBlock reads a (Simple)Block payload if it belongs to a wanted track;
// otherwise it only reads the track number and leaves the rest unread.
func (info *mkvInfo) readBlock(r *ebmlReader, want map[uint64]*mkvTrack, size int64, clusterTime, duration float64) (mkvCue, uint64, bool, error) {
	n, l, err := r.readVint(false)
	if err != nil {
		return mkvCue{}, 0, false, err
	}
	t, ok := want[n]
	if !ok {
		return mkvCue{}, 0, false, nil
	}
	hdr, err := r.readFull(3)
	if err != nil {
		return mkvCue{}, 0, false, err
	}
	rel := int16(binary.BigEndian.Uint16(hdr[:2]))
	payload, err := r.readFull(size - int64(l) - 3)
	if err != nil {
		return mkvCue{}, 0, false, err
	}
	data, err := decodePayload(t, payload)
	if err != nil {
		return mkvCue{}, 0, false, nil
	}
	start := clusterTime + float64(rel)*info.timecodeScale/1e9
	cue := mkvCue{Start: start, End: start + duration, Text: subtitleText(t.Codec, data)}
	return cue, n, true, nil
}

func decodePayload(t *mkvTrack, payload []byte) ([]byte, error) {
	switch t.compAlgo {
	case 0:
		zr, err := zlib.NewReader(bytes.NewReader(payload))
		if err != nil {
			return nil, err
		}
		defer zr.Close()
		return io.ReadAll(io.LimitReader(zr, 1<<20))
	case 3:
		return append(append([]byte{}, t.compSettings...), payload...), nil
	}
	return payload, nil
}

// subtitleText turns a block payload into plain text lines. ASS/SSA blocks
// are "ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text".
func subtitleText(codec string, data []byte) string {
	s := strings.TrimRight(string(data), "\x00")
	switch codec {
	case "S_TEXT/ASS", "S_TEXT/SSA", "S_ASS", "S_SSA":
		parts := strings.SplitN(s, ",", 9)
		if len(parts) == 9 {
			s = parts[8]
		}
		if strings.Contains(s, `\p1`) || strings.Contains(s, `\p2`) {
			return "" // vector drawing, not text
		}
		s = strings.NewReplacer(`\N`, "\n", `\n`, "\n", `\h`, " ").Replace(s)
	}
	return strings.TrimSpace(strings.ReplaceAll(s, "\r\n", "\n"))
}
