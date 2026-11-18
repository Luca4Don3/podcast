package rss

import (
	"bytes"
	"testing"

	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/transform"
)

// 中文播客常见的 GBK 编码源：不转码会全程乱码。
func TestParseGBKFeed(t *testing.T) {
	utf8Feed := `<?xml version="1.0" encoding="GBK"?>
<rss version="2.0"><channel>
<title>中文播客</title>
<description>测试编码</description>
<item><title>第一期</title><guid>g1</guid>
<enclosure url="https://example.com/1.mp3" type="audio/mpeg"/></item>
</channel></rss>`

	var buf bytes.Buffer
	writer := transform.NewWriter(&buf, simplifiedchinese.GBK.NewEncoder())
	if _, err := writer.Write([]byte(utf8Feed)); err != nil {
		t.Fatalf("encode gbk: %v", err)
	}
	_ = writer.Close()

	feed, err := Parse(buf.Bytes())
	if err != nil {
		t.Fatalf("parse gbk: %v", err)
	}
	if feed.Title != "中文播客" {
		t.Fatalf("gbk title not decoded: %q", feed.Title)
	}
	if len(feed.Episodes) != 1 || feed.Episodes[0].Title != "第一期" {
		t.Fatalf("gbk episode not decoded: %+v", feed.Episodes)
	}
}

// 声明缺失但实际为 GBK 的源也要能兜底。
func TestParseUndeclaredGBKFeed(t *testing.T) {
	utf8Feed := `<rss version="2.0"><channel>
<title>无声明编码</title>
<item><title>单集</title><guid>g1</guid>
<enclosure url="https://example.com/1.mp3" type="audio/mpeg"/></item>
</channel></rss>`

	var buf bytes.Buffer
	writer := transform.NewWriter(&buf, simplifiedchinese.GB18030.NewEncoder())
	if _, err := writer.Write([]byte(utf8Feed)); err != nil {
		t.Fatalf("encode: %v", err)
	}
	_ = writer.Close()

	feed, err := Parse(buf.Bytes())
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if feed.Title != "无声明编码" {
		t.Fatalf("fallback decode failed: %q", feed.Title)
	}
}

// 带 HTTP Content-Type charset 的转码。
func TestParseWithContentTypeCharset(t *testing.T) {
	utf8Feed := `<?xml version="1.0" encoding="utf-8"?>
<rss version="2.0"><channel><title>UTF8 源</title>
<item><title>x</title><enclosure url="https://e/1.mp3"/></item></channel></rss>`
	feed, err := ParseWithContentType([]byte(utf8Feed), "application/rss+xml; charset=utf-8")
	if err != nil || feed.Title != "UTF8 源" {
		t.Fatalf("content-type parse: %v %+v", err, feed)
	}
}

// 超大 feed 的截断保护（由 Fetcher 的 LimitReader 保证；此处验证解析不 panic）。
func TestParseLargeFeedDoesNotPanic(t *testing.T) {
	big := bytes.Repeat([]byte("<item><title>t</title></item>"), 5000)
	header := `<rss version="2.0"><channel><title>大源</title>`
	feed := append([]byte(header), big...)
	feed = append(feed, []byte("</channel></rss>")...)
	if _, err := Parse(feed); err != nil {
		t.Fatalf("large feed parse: %v", err)
	}
}
