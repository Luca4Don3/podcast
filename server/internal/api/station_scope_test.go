package api

import (
	"context"
	"net/http"
	"testing"

	"github.com/podcastapp/server/internal/store"
)

func TestStationImportsAndFavoritesAreUserScoped(t *testing.T) {
	env := newTestEnv(t)
	owner, _ := env.register(t, "owner@example.com")
	other, _ := env.register(t, "other@example.com")
	rec, body := env.do(t, http.MethodPost, "/api/v1/stations/import", owner, map[string]any{
		"stations": []map[string]any{{"name": "Private import", "streamUrl": "https://example.invalid/private.mp3"}},
	})
	if rec.Code != http.StatusOK || body["imported"] != float64(1) {
		t.Fatalf("import: status=%d body=%s", rec.Code, rec.Body.String())
	}
	item := body["items"].([]any)[0].(map[string]any)
	privateID := item["id"].(string)
	if item["source"] != "manual" {
		t.Fatalf("import source must be manual: %v", item)
	}
	rec, body = env.do(t, http.MethodGet, "/api/v1/stations", other, nil)
	if rec.Code != http.StatusOK || body["total"] != float64(0) {
		t.Fatalf("private import leaked: status=%d body=%s", rec.Code, rec.Body.String())
	}
	for _, method := range []string{http.MethodPut, http.MethodPost, http.MethodDelete} {
		for _, id := range []string{privateID, "missing"} {
			rec, body = env.do(t, method, "/api/v1/stations/"+id+"/favorite", other, map[string]any{"favorite": true})
			if rec.Code != http.StatusNotFound || body["code"] != "not_found" {
				t.Fatalf("%s favorite must hide private/missing stations: status=%d body=%s", method, rec.Code, rec.Body.String())
			}
		}
	}
	rec, _ = env.do(t, http.MethodDelete, "/api/v1/stations/"+privateID, other, nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("other user's delete status=%d", rec.Code)
	}
	rec, _ = env.do(t, http.MethodPut, "/api/v1/stations/"+privateID+"/favorite", owner, map[string]any{"favorite": true})
	if rec.Code != http.StatusOK {
		t.Fatalf("owner favorite status=%d", rec.Code)
	}

	// Explicit public records remain favoritable; importing never creates such a record.
	publicID, err := env.api.store.UpsertStation(context.Background(), &store.Station{
		ID: newID(), Name: "Public fixture", StreamURL: "https://example.invalid/public.mp3", Source: "curated",
	})
	if err != nil {
		t.Fatal(err)
	}
	rec, _ = env.do(t, http.MethodPut, "/api/v1/stations/"+publicID+"/favorite", other, map[string]any{"favorite": true})
	if rec.Code != http.StatusOK {
		t.Fatalf("public favorite status=%d", rec.Code)
	}

	// Defense in depth for stale cross-user favorites written before this fix.
	var otherID string
	if err := env.api.store.DB.QueryRow("SELECT id FROM users WHERE email = ?", "other@example.com").Scan(&otherID); err != nil {
		t.Fatal(err)
	}
	_, err = env.api.store.DB.Exec("INSERT INTO station_favorites (id,user_id,station_id,created_at,updated_at,deleted) VALUES (?,?,?,?,?,0)",
		newID(), otherID, privateID, store.Now(), store.Now())
	if err != nil {
		t.Fatal(err)
	}
	rec, body = env.do(t, http.MethodGet, "/api/v1/stations", other, nil)
	if rec.Code != http.StatusOK || body["total"] != float64(1) || body["items"].([]any)[0].(map[string]any)["id"] != publicID {
		t.Fatalf("stale favorite leaked private import: status=%d body=%s", rec.Code, rec.Body.String())
	}
	rec, body = env.do(t, http.MethodPost, "/api/v1/stations/import", other, map[string]any{
		"stations": []map[string]any{{"streamUrl": "https://example.invalid/other.mp3"}},
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("other import status=%d body=%s", rec.Code, rec.Body.String())
	}
	for _, value := range body["items"].([]any) {
		if value.(map[string]any)["id"] == privateID {
			t.Fatal("import response leaked stale private favorite")
		}
	}
	rec, _ = env.do(t, http.MethodDelete, "/api/v1/stations/"+privateID, owner, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("owner delete status=%d", rec.Code)
	}
	for _, method := range []string{http.MethodPut, http.MethodPost, http.MethodDelete} {
		rec, _ = env.do(t, method, "/api/v1/stations/"+privateID+"/favorite", owner, map[string]any{"favorite": true})
		if rec.Code != http.StatusNotFound {
			t.Fatalf("deleted station %s favorite status=%d", method, rec.Code)
		}
	}
}
