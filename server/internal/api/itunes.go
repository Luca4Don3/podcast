package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type itunesClient struct {
	endpoint string
	http     *http.Client
}

// DiscoverItem 为外部目录条目。
type DiscoverItem struct {
	Name         string `json:"name"`
	Artist       string `json:"artist"`
	FeedURL      string `json:"feedUrl"`
	ArtworkURL   string `json:"artworkUrl"`
	Genre        string `json:"genre"`
	EpisodeCount int    `json:"episodeCount"`
	Country      string `json:"country"`
}

func newItunesClient(endpoint string) *itunesClient {
	return &itunesClient{endpoint: strings.TrimSpace(endpoint), http: &http.Client{Timeout: 12 * time.Second}}
}

func (c *itunesClient) Enabled() bool {
	return c != nil && strings.TrimSpace(c.endpoint) != ""
}

func (c *itunesClient) Search(ctx context.Context, term string, limit int, country string) ([]DiscoverItem, error) {
	// 请求层也守住禁用状态，任何调用者均不能触发默认外部请求。
	if !c.Enabled() {
		return []DiscoverItem{}, nil
	}
	if country == "" {
		country = "CN"
	}
	q := url.Values{}
	q.Set("term", term)
	q.Set("media", "podcast")
	q.Set("entity", "podcast")
	q.Set("limit", fmt.Sprintf("%d", limit))
	q.Set("country", country)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.endpoint+"?"+q.Encode(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("itunes: status %d", resp.StatusCode)
	}

	var payload struct {
		Results []struct {
			CollectionName string `json:"collectionName"`
			ArtistName     string `json:"artistName"`
			FeedURL        string `json:"feedUrl"`
			ArtworkURL     string `json:"artworkUrl600"`
			PrimaryGenre   string `json:"primaryGenreName"`
			TrackCount     int    `json:"trackCount"`
			Country        string `json:"country"`
		} `json:"results"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return nil, err
	}
	out := make([]DiscoverItem, 0, len(payload.Results))
	for _, r := range payload.Results {
		if r.FeedURL == "" {
			continue
		}
		out = append(out, DiscoverItem{
			Name:         r.CollectionName,
			Artist:       r.ArtistName,
			FeedURL:      r.FeedURL,
			ArtworkURL:   r.ArtworkURL,
			Genre:        r.PrimaryGenre,
			EpisodeCount: r.TrackCount,
			Country:      r.Country,
		})
	}
	return out, nil
}
