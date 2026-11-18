package rss

import (
	"bytes"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/text/encoding/charmap"
	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/encoding/traditionalchinese"
	"golang.org/x/text/transform"
)

// Feed 为解析后的播客订阅源。
type Feed struct {
	Title       string
	Author      string
	Description string
	ImageURL    string
	Link        string
	Language    string
	Category    string
	Explicit    bool
	ETag        string
	LastMod     string
	Episodes    []Episode
}

// Episode 为解析后的单集。
type Episode struct {
	GUID          string
	Title         string
	Description   string
	AudioURL      string
	DurationSec   int
	PublishedAt   time.Time
	ImageURL      string
	EpisodeNumber int
	SeasonNumber  int
}

// ErrNotFeed 表示响应不是可识别的 RSS/Atom 源。
var ErrNotFeed = errors.New("rss: not a recognizable feed")

type rssDoc struct {
	XMLName xml.Name   `xml:"rss"`
	Channel rssChannel `xml:"channel"`
}

type rssChannel struct {
	Title         string `xml:"title"`
	SubTitle      string `xml:"subtitle"`
	Description   string `xml:"description"`
	Link          string `xml:"link"`
	Language      string `xml:"language"`
	Author        string `xml:"author"`
	ItunesAuthor  string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd author"`
	ItunesTitle   string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd title"`
	ItunesSummary string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd summary"`
	ItunesImage   struct {
		Href string `xml:"href,attr"`
	} `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd image"`
	ItunesCategory struct {
		Text string `xml:"text,attr"`
	} `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd category"`
	ItunesExplicit string    `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd explicit"`
	Image          rssImage  `xml:"image"`
	Items          []rssItem `xml:"item"`
}

type rssImage struct {
	URL string `xml:"url"`
}

type rssItem struct {
	Title          string `xml:"title"`
	Description    string `xml:"description"`
	Summary        string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd summary"`
	PubDate        string `xml:"pubDate"`
	GUID           string `xml:"guid"`
	Link           string `xml:"link"`
	ItunesTitle    string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd title"`
	ItunesDuration string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd duration"`
	ItunesImage    struct {
		Href string `xml:"href,attr"`
	} `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd image"`
	ItunesEpisode string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd episode"`
	ItunesSeason  string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd season"`
	Enclosures    []struct {
		URL    string `xml:"url,attr"`
		Type   string `xml:"type,attr"`
		Length string `xml:"length,attr"`
	} `xml:"enclosure"`
}

// Fetcher 拉取并解析订阅源。
type Fetcher struct {
	Client    *http.Client
	UserAgent string
	MaxBytes  int64
}

// NewFetcher 构造抓取器；跟随重定向并限制总大小。
func NewFetcher(userAgent string, maxBytes int64, timeout time.Duration) *Fetcher {
	if maxBytes <= 0 {
		maxBytes = 20 << 20
	}
	if timeout <= 0 {
		timeout = 20 * time.Second
	}
	return &Fetcher{
		Client: &http.Client{
			Timeout: timeout,
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				if len(via) >= 10 {
					return errors.New("rss: too many redirects")
				}
				return nil
			},
		},
		UserAgent: userAgent,
		MaxBytes:  maxBytes,
	}
}

// Fetch 下载并解析；etag/lastModified 命中 304 时返回 (nil, true, nil)。
func (f *Fetcher) Fetch(feedURL, etag, lastModified string) (*Feed, bool, error) {
	req, err := http.NewRequest(http.MethodGet, feedURL, nil)
	if err != nil {
		return nil, false, err
	}
	req.Header.Set("User-Agent", f.UserAgent)
	req.Header.Set("Accept", "application/rss+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5")
	req.Header.Set("Accept-Encoding", "gzip, deflate")
	if etag != "" {
		req.Header.Set("If-None-Match", etag)
	}
	if lastModified != "" {
		req.Header.Set("If-Modified-Since", lastModified)
	}

	resp, err := f.Client.Do(req)
	if err != nil {
		return nil, false, err
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusNotModified:
		return nil, true, nil
	case http.StatusOK:
	default:
		return nil, false, fmt.Errorf("rss: unexpected status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, f.MaxBytes))
	if err != nil {
		return nil, false, err
	}
	feed, err := ParseWithContentType(body, resp.Header.Get("Content-Type"))
	if err != nil {
		return nil, false, err
	}
	feed.ETag = resp.Header.Get("ETag")
	feed.LastMod = resp.Header.Get("Last-Modified")
	return feed, false, nil
}

// Parse 解析 RSS 2.0（自动处理 GBK/GB18030/Big5 等非 UTF-8 源）。
func Parse(body []byte) (*Feed, error) {
	return ParseWithContentType(body, "")
}

// ParseWithContentType 在解析前按 XML 声明 / HTTP charset 转码为 UTF-8。
func ParseWithContentType(body []byte, contentType string) (*Feed, error) {
	decoded := decodeToUTF8(body, contentType)

	var doc rssDoc
	decoder := xml.NewDecoder(bytes.NewReader(decoded))
	decoder.Strict = false
	decoder.CharsetReader = func(charset string, input io.Reader) (io.Reader, error) {
		return input, nil // 已在上游统一转码，这里放行
	}
	if err := decoder.Decode(&doc); err != nil {
		return nil, ErrNotFeed
	}
	ch := doc.Channel
	if strings.TrimSpace(ch.Title) == "" && len(ch.Items) == 0 {
		return nil, ErrNotFeed
	}

	feed := &Feed{
		Title:       firstNonEmpty(ch.ItunesTitle, ch.Title),
		Author:      firstNonEmpty(ch.ItunesAuthor, ch.Author),
		Description: firstNonEmpty(ch.ItunesSummary, ch.Description, ch.SubTitle),
		ImageURL:    firstNonEmpty(ch.ItunesImage.Href, ch.Image.URL),
		Link:        strings.TrimSpace(ch.Link),
		Language:    strings.TrimSpace(ch.Language),
		Category:    strings.TrimSpace(ch.ItunesCategory.Text),
		Explicit:    isExplicit(ch.ItunesExplicit),
	}

	for _, it := range ch.Items {
		audioURL := ""
		for _, enc := range it.Enclosures {
			t := strings.ToLower(enc.Type)
			if strings.HasPrefix(t, "audio/") || t == "" || strings.HasPrefix(t, "video/mp4") ||
				strings.HasPrefix(t, "application/octet-stream") {
				audioURL = strings.TrimSpace(enc.URL)
				break
			}
		}
		if audioURL == "" && len(it.Enclosures) > 0 {
			audioURL = strings.TrimSpace(it.Enclosures[0].URL)
		}
		if audioURL == "" {
			continue
		}

		guid := strings.TrimSpace(it.GUID)
		if guid == "" {
			guid = audioURL
		}
		ep := Episode{
			GUID:          guid,
			Title:         firstNonEmpty(it.ItunesTitle, it.Title, "未命名单集"),
			Description:   firstNonEmpty(it.Summary, it.Description),
			AudioURL:      audioURL,
			DurationSec:   parseDuration(it.ItunesDuration),
			PublishedAt:   parsePubDate(it.PubDate),
			ImageURL:      it.ItunesImage.Href,
			EpisodeNumber: atoiSafe(it.ItunesEpisode),
			SeasonNumber:  atoiSafe(it.ItunesSeason),
		}
		feed.Episodes = append(feed.Episodes, ep)
	}
	return feed, nil
}

// decodeToUTF8 统一把常见非 UTF-8 编码转成 UTF-8，避免中文播客乱码。
func decodeToUTF8(body []byte, contentType string) []byte {
	charset := ""
	if _, params, err := mime.ParseMediaType(contentType); err == nil {
		charset = strings.ToLower(params["charset"])
	}
	if charset == "" {
		head := body
		if len(head) > 200 {
			head = head[:200]
		}
		if idx := bytes.Index(bytes.ToLower(head), []byte("encoding=")); idx >= 0 {
			rest := head[idx+len("encoding="):]
			rest = bytes.TrimLeft(rest, "\"' ")
			end := 0
			for end < len(rest) && (isAlphaNum(rest[end]) || rest[end] == '-') {
				end++
			}
			charset = strings.ToLower(string(rest[:end]))
		}
	}
	if charset == "" || charset == "utf-8" || charset == "utf8" {
		if utf8.Valid(body) {
			return body
		}
		// 声明缺失但实际不是 UTF-8，按最常见的 GB18030 兜底
		return transformBytes(body, simplifiedchinese.GB18030.NewDecoder())
	}

	switch charset {
	case "gbk", "gb2312", "gb-2312", "gb18030", "x-gbk":
		return transformBytes(body, simplifiedchinese.GB18030.NewDecoder())
	case "big5", "big-5", "cp950":
		return transformBytes(body, traditionalchinese.Big5.NewDecoder())
	case "iso-8859-1", "latin1", "us-ascii", "ascii":
		return transformBytes(body, charmap.ISO8859_1.NewDecoder())
	case "windows-1252", "cp1252":
		return transformBytes(body, charmap.Windows1252.NewDecoder())
	default:
		return body
	}
}

func transformBytes(body []byte, t transform.Transformer) []byte {
	out, _, err := transform.Bytes(t, body)
	if err != nil {
		return body
	}
	return out
}

func isAlphaNum(b byte) bool {
	return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || b == '_'
}

func parsePubDate(v string) time.Time {
	v = strings.TrimSpace(v)
	if v == "" {
		return time.Now().UTC()
	}
	layouts := []string{
		time.RFC1123Z, time.RFC1123, time.RFC822Z, time.RFC822,
		"Mon, 2 Jan 2006 15:04:05 -0700", "Mon, 2 Jan 2006 15:04:05 MST",
		"2006-01-02T15:04:05Z07:00", "2006-01-02 15:04:05", "2006-01-02",
	}
	for _, l := range layouts {
		if t, err := time.Parse(l, v); err == nil {
			return t.UTC()
		}
	}
	return time.Now().UTC()
}

func parseDuration(v string) int {
	v = strings.TrimSpace(v)
	if v == "" {
		return 0
	}
	if strings.Contains(v, ":") {
		parts := strings.Split(v, ":")
		total := 0
		for _, p := range parts {
			n, err := strconv.Atoi(strings.TrimSpace(p))
			if err != nil {
				return 0
			}
			total = total*60 + n
		}
		return total
	}
	if n, err := strconv.Atoi(v); err == nil {
		return n
	}
	return 0
}

func atoiSafe(v string) int {
	n, err := strconv.Atoi(strings.TrimSpace(v))
	if err != nil {
		return 0
	}
	return n
}

func isExplicit(v string) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "yes", "true", "explicit", "1":
		return true
	}
	return false
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if s := strings.TrimSpace(v); s != "" {
			return s
		}
	}
	return ""
}
