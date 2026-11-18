package rss

import (
	"strings"
	"testing"
	"time"
)

const feedWithItunes = `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
<channel>
  <title>频道标题</title>
  <description>描述</description>
  <language>zh-cn</language>
  <itunes:author>主播</itunes:author>
  <itunes:image href="https://example.com/cover.jpg"/>
  <itunes:category text="Technology"/>
  <itunes:explicit>yes</itunes:explicit>
  <item>
    <title>单集一</title>
    <guid>g1</guid>
    <pubDate>Mon, 14 Oct 2024 08:00:00 GMT</pubDate>
    <itunes:duration>01:02:03</itunes:duration>
    <itunes:episode>7</itunes:episode>
    <itunes:season>2</itunes:season>
    <enclosure url="https://example.com/a.mp3" type="audio/mpeg" length="1"/>
  </item>
  <item>
    <title>单集二</title>
    <guid>g2</guid>
    <itunes:duration>95</itunes:duration>
    <enclosure url="https://example.com/b.m4a" type="audio/x-m4a"/>
  </item>
  <item>
    <title>无音频项</title>
    <guid>g3</guid>
  </item>
</channel>
</rss>`

func TestParseFeed(t *testing.T) {
	feed, err := Parse([]byte(feedWithItunes))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if feed.Title != "频道标题" || feed.Author != "主播" {
		t.Fatalf("unexpected feed meta: %+v", feed)
	}
	if feed.Category != "Technology" || !feed.Explicit || feed.Language != "zh-cn" {
		t.Fatalf("unexpected feed attrs: %+v", feed)
	}
	if feed.ImageURL != "https://example.com/cover.jpg" {
		t.Fatalf("unexpected image: %s", feed.ImageURL)
	}
	// 无 enclosure 的条目会被跳过
	if len(feed.Episodes) != 2 {
		t.Fatalf("expected 2 episodes, got %d", len(feed.Episodes))
	}
	if feed.Episodes[0].DurationSec != 3723 {
		t.Fatalf("hh:mm:ss duration: %d", feed.Episodes[0].DurationSec)
	}
	if feed.Episodes[0].EpisodeNumber != 7 || feed.Episodes[0].SeasonNumber != 2 {
		t.Fatalf("episode/season: %+v", feed.Episodes[0])
	}
	if feed.Episodes[1].DurationSec != 95 {
		t.Fatalf("numeric duration: %d", feed.Episodes[1].DurationSec)
	}
	want := time.Date(2024, 10, 14, 8, 0, 0, 0, time.UTC)
	if !feed.Episodes[0].PublishedAt.Equal(want) {
		t.Fatalf("pubDate: %v", feed.Episodes[0].PublishedAt)
	}
	// 无 guid 时回退到音频地址
	if feed.Episodes[1].GUID != "g2" {
		t.Fatalf("guid: %s", feed.Episodes[1].GUID)
	}
}

func TestParseRejectsNonFeed(t *testing.T) {
	if _, err := Parse([]byte("<html><body>not a feed</body></html>")); err == nil {
		t.Fatal("expected error for non-feed payload")
	}
	if _, err := Parse([]byte(strings.Repeat("x", 100))); err == nil {
		t.Fatal("expected error for garbage payload")
	}
}
