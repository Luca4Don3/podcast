#ifndef _SILENCE_EXPERIMENTAL_COROUTINE_DEPRECATION_WARNINGS
#define _SILENCE_EXPERIMENTAL_COROUTINE_DEPRECATION_WARNINGS
#endif

#include <windows.h>
#include <commctrl.h>
#include <commdlg.h>
#include <shellapi.h>
#include <cstdio>
#include <cwchar>
#include <cwctype>
#include <string>
#include <vector>

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.Core.h>
#include <winrt/Windows.Media.Playback.h>
#include <winrt/Windows.Storage.h>

using namespace winrt;
using namespace Windows::Foundation;
using namespace Windows::Media::Core;
using namespace Windows::Media::Playback;
using namespace Windows::Storage;

namespace {

constexpr int kUrlEdit = 1001;
constexpr int kLoadUrl = 1002;
constexpr int kOpenLocal = 1003;
constexpr int kPlayPause = 1004;
constexpr int kStop = 1005;
constexpr int kSeekBack = 1006;
constexpr int kSeekForward = 1007;
constexpr int kStatus = 1008;
constexpr int kRateDown = 1009;
constexpr int kRateUp = 1010;
constexpr UINT kPositionMessage = WM_APP + 1;

HINSTANCE g_instance = nullptr;
MediaPlayer g_player = nullptr;
MediaPlaybackSession g_session = nullptr;
winrt::event_token g_position_token;
bool g_position_hooked = false;
HWND g_status = nullptr;
HWND g_window = nullptr;
std::vector<double> g_rates = {0.75, 1.0, 1.25, 1.5, 2.0};
size_t g_rate_index = 1;
const wchar_t *kWindowClass = L"PodcastWindowsW0";

void set_status(const std::wstring &text) {
    if (g_status) SetWindowTextW(g_status, text.c_str());
}

std::wstring from_hstring(const winrt::hstring &value) {
    return std::wstring(value.c_str(), value.size());
}

std::wstring format_time(const TimeSpan &value) {
    const int64_t seconds = value.count() / 10000000;
    wchar_t buffer[64];
    swprintf(buffer, 64, L"%02lld:%02lld",
             static_cast<long long>(seconds / 60), static_cast<long long>(seconds % 60));
    return buffer;
}

void refresh_status() {
    if (!g_session) return;
    const TimeSpan position = g_session.Position();
    const TimeSpan duration = g_session.NaturalDuration();
    const double rate = g_session.PlaybackRate();
    wchar_t buffer[192];
    swprintf(buffer, 192, L"%s / %s   %.2fx   %s",
             format_time(position).c_str(), format_time(duration).c_str(), rate,
             g_session.PlaybackState() == MediaPlaybackState::Playing ? L"播放中" : L"已暂停");
    set_status(buffer);
}

void hook_session_events() {
    if (g_position_hooked || !g_session) return;
    g_position_token = g_session.PositionChanged([](const MediaPlaybackSession &, const IInspectable &) {
        if (g_window) PostMessageW(g_window, kPositionMessage, 0, 0);
    });
    g_position_hooked = true;
}

bool valid_public_url(const std::wstring &text, Uri &uri) {
    if (text.empty() || text.find(L'@') != std::wstring::npos) return false;
    const bool http = text.compare(0, 7, L"http://") == 0;
    const bool https = text.compare(0, 8, L"https://") == 0;
    if (!http && !https) return false;
    try {
        uri = Uri(text);
        return !uri.Host().empty();
    } catch (...) {
        return false;
    }
}

void set_title(MediaPlaybackItem &item, const std::wstring &title) {
    try {
        auto props = item.GetDisplayProperties();
        props.MusicProperties().Title(title);
        item.ApplyDisplayProperties(props);
    } catch (...) { /* Metadata is optional for playback. */ }
}

void load_uri(const Uri &uri, const std::wstring &title) {
    auto item = MediaPlaybackItem(MediaSource::CreateFromUri(uri));
    set_title(item, title);
    g_player.Source(item);
    hook_session_events();
    set_status(L"已加载：" + title);
}

void load_uri(const std::wstring &text) {
    Uri uri(nullptr);
    if (!valid_public_url(text, uri)) {
        set_status(L"地址无效：仅允许不含账号信息的 http/https URL");
        return;
    }
    try {
        load_uri(uri, text);
    } catch (...) {
        set_status(L"无法打开该地址");
    }
}

void open_local_file(HWND hwnd) {
    wchar_t path[MAX_PATH * 4] = {};
    OPENFILENAMEW ofn = {};
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = hwnd;
    ofn.lpstrFilter = L"Audio files\0*.mp3;*.m4a;*.aac;*.flac;*.ogg;*.opus;*.wav;*.wma\0All files\0*.*\0\0";
    ofn.lpstrFile = path;
    ofn.nMaxFile = MAX_PATH * 4;
    ofn.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST;
    if (!GetOpenFileNameW(&ofn)) return;
    try {
        StorageFile file = StorageFile::GetFileFromPathAsync(path).get();
        auto item = MediaPlaybackItem(MediaSource::CreateFromStorageFile(file));
        const std::wstring name = from_hstring(file.Name());
        set_title(item, name);
        g_player.Source(item);
        hook_session_events();
        set_status(L"已加载本地文件：" + name);
    } catch (...) {
        set_status(L"无法打开本地文件");
    }
}

LRESULT CALLBACK window_proc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_CREATE: {
        g_window = hwnd;
        const DWORD style = WS_CHILD | WS_VISIBLE | WS_TABSTOP;
        CreateWindowExW(0, L"EDIT", L"", WS_CHILD | WS_VISIBLE | WS_TABSTOP | WS_BORDER,
                        16, 16, 520, 24, hwnd, reinterpret_cast<HMENU>(kUrlEdit), g_instance, nullptr);
        struct Button { int id; const wchar_t *label; int x; int y; int width; };
        const Button buttons[] = {
            {kLoadUrl, L"加载 URL", 16, 52, 96},
            {kOpenLocal, L"打开本地文件", 122, 52, 120},
            {kPlayPause, L"播放 / 暂停", 252, 52, 112},
            {kStop, L"停止", 374, 52, 72},
            {kSeekBack, L"-15 秒", 456, 52, 76},
            {kSeekForward, L"+30 秒", 542, 52, 76},
            {kRateDown, L"慢速", 16, 88, 76},
            {kRateUp, L"快速", 102, 88, 76},
        };
        for (const auto &b : buttons)
            CreateWindowExW(0, L"BUTTON", b.label, style | BS_PUSHBUTTON,
                            b.x, b.y, b.width, 28, hwnd, reinterpret_cast<HMENU>(b.id), g_instance, nullptr);
        g_status = CreateWindowExW(0, L"STATIC", L"就绪", WS_CHILD | WS_VISIBLE,
                                   16, 132, 600, 24, hwnd, reinterpret_cast<HMENU>(kStatus), g_instance, nullptr);
        return 0;
    }
    case kPositionMessage:
        refresh_status();
        return 0;
    case WM_COMMAND: {
        const int id = LOWORD(wparam);
        if (id == kLoadUrl) {
            wchar_t text[2048] = {};
            GetWindowTextW(GetDlgItem(hwnd, kUrlEdit), text, 2048);
            load_uri(text);
        } else if (id == kOpenLocal) {
            open_local_file(hwnd);
        } else if (id == kPlayPause) {
            if (!g_session || !g_player.Source()) { set_status(L"请先加载音频"); break; }
            if (g_session.PlaybackState() == MediaPlaybackState::Playing) g_player.Pause();
            else g_player.Play();
            refresh_status();
        } else if (id == kStop) {
            if (g_player.Source()) g_player.Source(nullptr);
            set_status(L"已停止");
        } else if (id == kSeekBack || id == kSeekForward) {
            if (g_session && g_session.CanSeek()) {
                const int64_t delta = (id == kSeekBack ? -15 : 30) * 10000000LL;
                const int64_t next = g_session.Position().count() + delta;
                g_session.Position(TimeSpan(next < 0 ? 0 : next));
                refresh_status();
            } else set_status(L"当前音源不可定位");
        } else if (id == kRateDown || id == kRateUp) {
            if (!g_session) break;
            if (id == kRateDown && g_rate_index > 0) --g_rate_index;
            if (id == kRateUp && g_rate_index + 1 < g_rates.size()) ++g_rate_index;
            g_session.PlaybackRate(g_rates[g_rate_index]);
            refresh_status();
        }
        return 0;
    }
    case WM_DESTROY:
        if (g_position_hooked && g_session) g_session.PositionChanged(g_position_token);
        PostQuitMessage(0);
        return 0;
    default:
        return DefWindowProcW(hwnd, message, wparam, lparam);
    }
}

int run(int argc, wchar_t **argv) {
    init_apartment(apartment_type::single_threaded);
    g_player = MediaPlayer();
    g_session = g_player.PlaybackSession();
    if (!g_player || !g_session) return 2;

    WNDCLASSW wc = {};
    wc.lpfnWndProc = window_proc;
    wc.hInstance = g_instance;
    wc.lpszClassName = kWindowClass;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    if (!RegisterClassW(&wc)) return 3;

    HWND hwnd = CreateWindowExW(0, kWindowClass, L"Podcast W0",
        WS_OVERLAPPEDWINDOW | WS_CLIPCHILDREN, CW_USEDEFAULT, CW_USEDEFAULT,
        640, 240, nullptr, nullptr, g_instance, nullptr);
    if (!hwnd) return 4;

    bool smoke = false;
    for (int i = 1; i < argc; ++i)
        if (std::wcscmp(argv[i], L"--smoke-test") == 0) smoke = true;
    if (smoke) {
        set_status(L"冒烟测试通过");
        PostQuitMessage(0);
    } else {
        ShowWindow(hwnd, SW_SHOW);
        UpdateWindow(hwnd);
        set_status(L"请加载本地音频或输入 http(s) 地址");
    }

    MSG msg = {};
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    g_player.Source(nullptr);
    g_player = nullptr;
    g_session = nullptr;
    return 0;
}

} // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
    g_instance = instance;
    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    const int result = run(argc, argv ? argv : nullptr);
    if (argv) LocalFree(argv);
    return result;
}
