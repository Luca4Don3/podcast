package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDirectoryConfigurationRequiresExplicitURL(t *testing.T) {
	t.Setenv("APP_ENV", "test")
	t.Setenv("JWT_SECRET", "test-directory-secret-value")
	for _, tc := range []struct {
		name, value, want string
		unset             bool
	}{
		{name: "unset", unset: true},
		{name: "empty"},
		{name: "whitespace", value: " \t\r\n "},
		{name: "explicit", value: "  http://127.0.0.1:12345/catalog 	", want: "http://127.0.0.1:12345/catalog"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			for _, key := range []string{"ITUNES_SEARCH_URL", "RADIO_BROWSER_URL"} {
				t.Setenv(key, tc.value)
				if tc.unset {
					if err := os.Unsetenv(key); err != nil {
						t.Fatal(err)
					}
				}
			}
			cfg, err := Load()
			if err != nil {
				t.Fatal(err)
			}
			if cfg.ItunesSearchURL != tc.want || cfg.RadioBrowserURL != tc.want {
				t.Fatalf("directory URLs = %q, %q; want %q", cfg.ItunesSearchURL, cfg.RadioBrowserURL, tc.want)
			}
		})
	}
}

func TestDotEnvEmptyDirectoryValuesStayDisabled(t *testing.T) {
	t.Setenv("APP_ENV", "test")
	t.Setenv("JWT_SECRET", "test-directory-secret-value")
	for _, key := range []string{"ITUNES_SEARCH_URL", "RADIO_BROWSER_URL"} {
		t.Setenv(key, "")
		if err := os.Unsetenv(key); err != nil {
			t.Fatal(err)
		}
	}
	path := filepath.Join(t.TempDir(), "directories.env")
	if err := os.WriteFile(path, []byte("ITUNES_SEARCH_URL=\"\"\nRADIO_BROWSER_URL='  '\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := LoadDotEnv(path); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ItunesSearchURL != "" || cfg.RadioBrowserURL != "" {
		t.Fatal("quoted empty/blank dotenv values must disable directories")
	}
}
