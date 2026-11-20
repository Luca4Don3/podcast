#ifndef _SILENCE_EXPERIMENTAL_COROUTINE_DEPRECATION_WARNINGS
#define _SILENCE_EXPERIMENTAL_COROUTINE_DEPRECATION_WARNINGS
#endif

#include <windows.h>
#include <commctrl.h>
#include <commdlg.h>
#include <objbase.h>
#include <algorithm>
#include <atomic>
#include <fstream>
#include <filesystem>
#include <iterator>
#include <climits>
#include <map>
#include <set>
#include <thread>
#include <utility>
#include <shellapi.h>
#include <cstdio>
#include <cstdint>
#include <cwchar>
#include <cwctype>
#include <string>
#include <vector>

#include "feed_parser.h"
#include "http_client.h"
#include "local_store.h"

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
constexpr int kFeedUrlEdit = 1011;
constexpr int kAddFeed = 1012;
constexpr int kRefresh = 1013;
constexpr int kFeedList = 1014;
constexpr int kEpisodeList = 1015;
constexpr int kQueueList = 1016;
constexpr int kQueueUp = 1017;
constexpr int kQueueDown = 1018;
constexpr int kQueueRemove = 1019;
constexpr int kImportOpml = 1020;
constexpr int kQueueFirst = 1021;
constexpr int kQueueAppend = 1022;
constexpr int kEpisodePlay = 1023;
constexpr int kQueuePlay = 1024;
constexpr int kPlaybackInfo = 1025;
constexpr int kCancelFeeds = 1026;
constexpr UINT kPositionMessage = WM_APP + 1;
constexpr UINT kFeedDone = WM_APP + 2;
constexpr size_t kXmlLimit = 4 * 1024 * 1024;

HINSTANCE g_instance = nullptr;
MediaPlayer g_player = nullptr;
MediaPlaybackSession g_session = nullptr;
winrt::event_token g_position_token;
bool g_position_hooked = false;
HWND g_status = nullptr;
HWND g_window = nullptr;
std::vector<double> g_rates = {0.75, 1.0, 1.25, 1.5, 2.0};
size_t g_rate_index = 1;
const wchar_t *kWindowClass = L"PodcastWindowsW1";
podcast::LocalStore g_store;
podcast::PlayQueue g_queue;
bool g_smoke = false;
bool g_closing = false;
std::thread g_fetch;
std::atomic_bool g_cancel{false};
struct FeedResult { std::string url; podcast::ParsedFeed parsed; };
struct FeedBatch { std::vector<FeedResult> ready; size_t failed = 0; size_t skipped = 0; bool invalid_opml = false; };
FeedBatch g_batch;
std::string g_selected_feed;
std::vector<podcast::Episode> g_visible_episodes;

HWND control(HWND parent, int id) { return GetDlgItem(parent, id); }

void fill_list(HWND list, const std::vector<std::wstring>& values) {
    SendMessageW(list, LB_RESETCONTENT, 0, 0);
    for (const auto& value : values) SendMessageW(list, LB_ADDSTRING, 0, reinterpret_cast<LPARAM>(value.c_str()));
}

int list_selection(HWND list) {
    return static_cast<int>(SendMessageW(list, LB_GETCURSEL, 0, 0));
}

std::wstring wide(const std::string& value) {
    if (value.empty() || value.size() > INT_MAX) return {};
    const auto size = static_cast<int>(value.size());
    const int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), size, nullptr, 0);
    if (!count) return {};
    std::wstring result(static_cast<size_t>(count), L'\0');
    if (!MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), size, result.data(), count)) return {};
    return result;
}

std::string narrow(const std::wstring& value) {
    if (value.empty() || value.size() > INT_MAX) return {};
    const auto size = static_cast<int>(value.size());
    const int count = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value.data(), size, nullptr, 0, nullptr, nullptr);
    if (!count) return {};
    std::string result(static_cast<size_t>(count), '\0');
    if (!WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value.data(), size, result.data(), count, nullptr, nullptr)) return {};
    return result;
}

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
    if (!IsIconic(g_window)) SetWindowTextW(control(g_window, kPlaybackInfo), buffer);
}

void hook_session_events() {
    if (g_position_hooked || !g_session) return;
    g_position_token = g_session.PositionChanged([window = g_window](const MediaPlaybackSession &, const IInspectable &) {
        PostMessageW(window, kPositionMessage, 0, 0);
    });
    g_position_hooked = true;
}

bool valid_public_url(const std::wstring &text, Uri &uri) {
    if (!podcast::valid_http_url(narrow(text))) return false;
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
        load_uri(uri, L"网络音频");
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

std::string feed_key(const std::string& url) {
    std::uint64_t hash = 1469598103934665603ULL;
    for (unsigned char c : url) { hash ^= c; hash *= 1099511628211ULL; }
    char buffer[17];
    std::snprintf(buffer, sizeof(buffer), "%016llx", static_cast<unsigned long long>(hash));
    return std::string(buffer);
}

void refresh_feed_lists(HWND hwnd) {
    std::vector<std::wstring> names;
    int index = LB_ERR;
    for (const auto& feed : g_store.feeds()) {
        if (feed.id == g_selected_feed) index = static_cast<int>(names.size());
        names.push_back(feed.title.empty() ? L"未命名订阅" : wide(feed.title));
    }
    const HWND list = control(hwnd, kFeedList);
    fill_list(list, names);
    if (!names.empty()) {
        if (index == LB_ERR) index = 0;
        SendMessageW(list, LB_SETCURSEL, index, 0);
        g_selected_feed = g_store.feeds()[index].id;
    }
}

void refresh_episodes(HWND hwnd) {
    g_visible_episodes.clear();
    for (const auto& e : g_store.episodes())
        if (e.feed_id == g_selected_feed) g_visible_episodes.push_back(e);
    std::vector<std::wstring> titles;
    for (const auto& e : g_visible_episodes) titles.push_back(e.title.empty() ? L"未命名单集" : wide(e.title));
    fill_list(control(hwnd, kEpisodeList), titles);
}

const podcast::Episode* find_episode(const std::string& id) {
    const auto& episodes = g_store.episodes();
    const auto match = std::find_if(episodes.begin(), episodes.end(), [&](const auto& e) { return e.id == id; });
    return match == episodes.end() ? nullptr : &*match;
}

void refresh_queue(HWND hwnd, const std::string& select = {}) {
    std::vector<std::wstring> labels;
    std::map<std::string, std::wstring> titles;
    for (const auto& episode : g_store.episodes()) titles.emplace(episode.id, wide(episode.title));
    int index = LB_ERR;
    for (const auto& id : g_queue.items()) {
        if (id == select) index = static_cast<int>(labels.size());
        auto title = titles.find(id);
        labels.push_back(title == titles.end() ? L"来源暂不可用" : title->second.empty() ? L"未命名单集" : title->second);
    }
    const HWND list = control(hwnd, kQueueList);
    fill_list(list, labels);
    if (index != LB_ERR) SendMessageW(list, LB_SETCURSEL, index, 0);
}

void set_feed_busy(HWND hwnd, bool busy) {
    for (int id : {kAddFeed, kRefresh, kImportOpml}) EnableWindow(control(hwnd, id), !busy);
    EnableWindow(control(hwnd, kCancelFeeds), busy);
}

bool read_opml(const std::wstring& file, std::vector<podcast::OpmlEntry>& entries) {
    std::ifstream input(std::filesystem::path(file), std::ios::binary | std::ios::ate);
    if (!input) return false;
    const auto size = input.tellg();
    if (size <= 0 || size > static_cast<std::streamoff>(kXmlLimit)) return false;
    std::string xml(static_cast<size_t>(size), '\0');
    return input.seekg(0) && input.read(xml.data(), static_cast<std::streamsize>(size)) &&
        podcast::parse_opml(xml, entries);
}

void fetch_feeds(HWND hwnd, std::vector<std::string> urls, std::wstring opml = {}) {
    if (g_fetch.joinable()) return;
    g_cancel = false;
    g_batch = {};
    set_feed_busy(hwnd, true);
    set_status(opml.empty() ? L"正在获取订阅…" : L"正在导入 OPML…");
    try {
        g_fetch = std::thread([hwnd, urls = std::move(urls), opml = std::move(opml)]() mutable {
            const HRESULT apartment = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
            try {
                if (!opml.empty()) {
                    std::vector<podcast::OpmlEntry> entries;
                    if (!read_opml(opml, entries)) g_batch.invalid_opml = true;
                    else for (auto& entry : entries) urls.push_back(std::move(entry.xml_url));
                }
                std::set<std::string> seen;
                size_t batch_budget = 32 * 1024 * 1024;
                for (const auto& url : urls) {
                    if (g_cancel.load()) break;
                    if (!seen.insert(url).second || !podcast::valid_http_url(url)) { ++g_batch.skipped; continue; }
                    podcast::HttpResponse response;
                    if (!batch_budget || !podcast::http_get(url, std::min(kXmlLimit, batch_budget), response, &g_cancel)) {
                        ++g_batch.failed;
                        continue;
                    }
                    batch_budget -= response.body.size();
                    FeedResult result{url, {}};
                    if (podcast::parse_feed(std::string(response.body.begin(), response.body.end()), result.parsed)) {
                        auto& items = result.parsed.episodes;
                        items.erase(std::remove_if(items.begin(), items.end(), [](const auto& e) {
                            return !podcast::valid_http_url(e.audio_url);
                        }), items.end());
                        g_batch.ready.push_back(std::move(result));
                    } else ++g_batch.failed;
                }
            } catch (...) { ++g_batch.failed; }
            if (SUCCEEDED(apartment)) CoUninitialize();
            PostMessageW(hwnd, kFeedDone, 0, 0);
        });
    } catch (...) {
        set_feed_busy(hwnd, false);
        set_status(L"无法启动订阅任务");
    }
}

void add_feed(HWND hwnd) {
    wchar_t text[8193]{};
    GetWindowTextW(control(hwnd, kFeedUrlEdit), text, static_cast<int>(std::size(text)));
    const std::string url = narrow(text);
    if (!podcast::valid_http_url(url)) { set_status(L"请输入不带账号信息的 HTTP(S) 订阅地址"); return; }
    fetch_feeds(hwnd, {url});
}

void import_opml(HWND hwnd) {
    wchar_t path[32768]{};
    OPENFILENAMEW dialog{};
    dialog.lStructSize = sizeof(dialog);
    dialog.hwndOwner = hwnd;
    dialog.lpstrFilter = L"OPML / XML\0*.opml;*.xml\0\0";
    dialog.lpstrFile = path;
    dialog.nMaxFile = static_cast<DWORD>(std::size(path));
    dialog.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;
    if (GetOpenFileNameW(&dialog)) fetch_feeds(hwnd, {}, path);
}

void refresh_all_feeds(HWND hwnd) {
    std::vector<std::string> urls;
    for (const auto& feed : g_store.feeds()) urls.push_back(feed.url);
    if (urls.empty()) { set_status(L"还没有订阅"); return; }
    fetch_feeds(hwnd, std::move(urls));
}

void apply_feed(podcast::LocalStore& store, const FeedResult& result, std::string& selected) {
    podcast::Feed feed;
    const auto& feeds = store.feeds();
    const auto previous = std::find_if(feeds.begin(), feeds.end(), [&](const auto& f) { return f.url == result.url; });
    feed.id = previous == feeds.end() ? feed_key(result.url) : previous->id;
    feed.url = result.url;
    feed.title = result.parsed.title;
    std::multimap<std::string, std::string> legacy_by_url;
    for (const auto& e : store.episodes())
        if (e.feed_id == feed.id && e.id.find(':') == std::string::npos) legacy_by_url.emplace(e.audio_url, e.id);
    std::set<std::string> ids;
    std::vector<podcast::Episode> episodes;
    for (const auto& ep : result.parsed.episodes) {
        std::string id = feed.id + ":" + ep.id;
        const auto old = legacy_by_url.equal_range(ep.audio_url);
        if (old.first != old.second && std::next(old.first) == old.second) id = old.first->second;
        if (ids.insert(id).second) episodes.push_back({id, feed.id, ep.title, ep.audio_url, ep.published_at, ep.duration_ms});
    }
    store.addOrUpdateFeed(feed);
    store.setEpisodes(feed.id, episodes);
    if (selected.empty()) selected = feed.id;
}

void finish_feeds(HWND hwnd) {
    if (!g_fetch.joinable()) return;
    g_fetch.join(); // The worker posts only after publishing the result; no network wait here.
    set_feed_busy(hwnd, false);
    if (g_closing) { DestroyWindow(hwnd); return; }
    if (g_cancel.load()) { g_batch = {}; set_status(L"已取消导入或刷新，原资料库未改动"); return; }
    if (g_batch.invalid_opml) { set_status(L"不是有效的 OPML，或文件超过导入上限"); return; }
    if (!g_batch.ready.empty()) {
        auto next = g_store;
        auto selected = g_selected_feed;
        for (const auto& result : g_batch.ready) apply_feed(next, result, selected);
        if (!next.save()) { set_status(L"无法写入资料库，保留原有订阅"); return; }
        g_store = std::move(next);
        g_selected_feed = std::move(selected);
        refresh_feed_lists(hwnd);
        refresh_episodes(hwnd);
        refresh_queue(hwnd);
    }
    set_status(L"完成：成功 " + std::to_wstring(g_batch.ready.size()) + L"，失败 " +
               std::to_wstring(g_batch.failed) + L"，跳过 " + std::to_wstring(g_batch.skipped));
    g_batch = {};
}

void save_queue(HWND hwnd, podcast::PlayQueue next, const std::string& select = {}) {
    auto store = g_store;
    store.setQueue(next.items());
    if (!store.save()) { set_status(L"无法保存队列"); return; }
    g_store = std::move(store);
    g_queue = std::move(next);
    refresh_queue(hwnd, select);
}

void play_episode(const podcast::Episode* episode) {
    if (!episode) { set_status(L"单集暂不可用，请刷新订阅"); return; }
    Uri uri(nullptr);
    if (!valid_public_url(wide(episode->audio_url), uri)) { set_status(L"音频地址无效"); return; }
    try {
        load_uri(uri, episode->title.empty() ? L"播客单集" : wide(episode->title));
        g_player.Play();
    } catch (...) { set_status(L"无法播放该单集"); }
}

LRESULT CALLBACK window_proc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_CREATE: {
        g_window = hwnd;
        const DWORD style = WS_CHILD | WS_VISIBLE | WS_TABSTOP;
        const DWORD list_style = style | LBS_NOTIFY | LBS_NOINTEGRALHEIGHT | WS_VSCROLL;
        // Row 1: subscription URL + add/refresh.
        CreateWindowExW(0, L"EDIT", L"", WS_CHILD | WS_VISIBLE | WS_TABSTOP | WS_BORDER,
                        16, 12, 560, 24, hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kFeedUrlEdit)), g_instance, nullptr);
        struct Button { int id; const wchar_t *label; int x; int y; int width; };
        const Button top[] = {
            {kAddFeed, L"添加订阅", 586, 12, 96},
            {kRefresh, L"刷新", 690, 12, 72},
            {kImportOpml, L"导入 OPML", 772, 12, 104},
            {kCancelFeeds, L"取消", 884, 12, 72},
        };
        for (const auto& b : top)
            CreateWindowExW(0, L"BUTTON", b.label, style | BS_PUSHBUTTON, b.x, b.y, b.width, 24,
                            hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(b.id)), g_instance, nullptr);
        // Row 2: direct audio URL + local file + playback controls.
        CreateWindowExW(0, L"EDIT", L"", WS_CHILD | WS_VISIBLE | WS_TABSTOP | WS_BORDER,
                        16, 44, 416, 24, hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kUrlEdit)), g_instance, nullptr);
        const Button buttons[] = {
            {kLoadUrl, L"加载音频 URL", 440, 44, 100},
            {kOpenLocal, L"打开本地文件", 546, 44, 120},
            {kPlayPause, L"播放 / 暂停", 676, 44, 104},
            {kStop, L"停止", 788, 44, 64},
            {kSeekBack, L"-15 秒", 860, 44, 72},
            {kSeekForward, L"+30 秒", 940, 44, 72},
            {kRateDown, L"慢速", 16, 78, 72},
            {kRateUp, L"快速", 96, 78, 72},
            {kEpisodePlay, L"播放单集", 306, 78, 96},
            {kQueueFirst, L"移到队首", 410, 78, 96},
            {kQueueAppend, L"加入队尾", 514, 78, 96},
            {kQueuePlay, L"播放待播", 816, 78, 84},
            {kQueueUp, L"上移", 908, 78, 48},
            {kQueueDown, L"下移", 964, 78, 48},
            {kQueueRemove, L"移除", 1020, 78, 80},
        };
        for (const auto &b : buttons)
            CreateWindowExW(0, L"BUTTON", b.label, style | BS_PUSHBUTTON,
                            b.x, b.y, b.width, 26, hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(b.id)), g_instance, nullptr);
        CreateWindowExW(WS_EX_CLIENTEDGE, L"LISTBOX", L"", list_style,
                        16, 118, 280, 340, hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kFeedList)), g_instance, nullptr);
        CreateWindowExW(WS_EX_CLIENTEDGE, L"LISTBOX", L"", list_style,
                        306, 118, 500, 340, hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kEpisodeList)), g_instance, nullptr);
        CreateWindowExW(WS_EX_CLIENTEDGE, L"LISTBOX", L"", list_style,
                        816, 118, 284, 340, hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kQueueList)), g_instance, nullptr);
        g_status = CreateWindowExW(0, L"STATIC", L"就绪", WS_CHILD | WS_VISIBLE,
                                   16, 470, 1084, 24, hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kStatus)), g_instance, nullptr);
        CreateWindowExW(0, L"STATIC", L"", WS_CHILD | WS_VISIBLE,
                        16, 496, 1084, 24, hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kPlaybackInfo)), g_instance, nullptr);
        for (int id : {kFeedList, kEpisodeList, kQueueList, kImportOpml, kCancelFeeds})
            if (!control(hwnd, id)) return -1;
        SendMessageW(control(hwnd, kFeedUrlEdit), EM_SETLIMITTEXT, 8192, 0);
        if (!g_smoke) g_store.load();
        for (const auto& id : g_store.queue()) g_queue.append(id);
        set_feed_busy(hwnd, false);
        refresh_feed_lists(hwnd);
        refresh_episodes(hwnd);
        refresh_queue(hwnd);
        return 0;
    }
    case kPositionMessage:
        refresh_status();
        return 0;
    case kFeedDone:
        finish_feeds(hwnd);
        return 0;
    case WM_COMMAND: {
        const int id = LOWORD(wparam);
        const int notify = HIWORD(wparam);
        if (id == kFeedList && notify == LBN_SELCHANGE) {
            const int selected = list_selection(control(hwnd, kFeedList));
            if (selected >= 0 && static_cast<size_t>(selected) < g_store.feeds().size()) {
                g_selected_feed = g_store.feeds()[selected].id;
                refresh_episodes(hwnd);
            }
        } else if (id == kEpisodePlay || id == kQueueFirst || id == kQueueAppend ||
                   (id == kEpisodeList && notify == LBN_DBLCLK)) {
            const int selected = list_selection(control(hwnd, kEpisodeList));
            if (selected < 0 || static_cast<size_t>(selected) >= g_visible_episodes.size()) {
                set_status(L"请先选择单集");
                return 0;
            }
            const auto& episode = g_visible_episodes[selected];
            if (id == kEpisodePlay || id == kEpisodeList) play_episode(&episode);
            else {
                auto queue = g_queue;
                if (id == kQueueFirst) queue.set_current(episode.id); // Queue head does not change the player.
                else queue.append(episode.id);
                save_queue(hwnd, std::move(queue), episode.id);
            }
        } else if (id == kQueuePlay || (id == kQueueList && notify == LBN_DBLCLK)) {
            const int selected = list_selection(control(hwnd, kQueueList));
            if (selected >= 0 && static_cast<size_t>(selected) < g_queue.size()) play_episode(find_episode(g_queue.items()[selected]));
        } else if (id == kLoadUrl) {
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
        } else if (id == kImportOpml) {
            import_opml(hwnd);
        } else if (id == kCancelFeeds) {
            g_cancel = true;
            EnableWindow(control(hwnd, kCancelFeeds), FALSE);
            set_status(L"正在取消…");
        } else if (id == kAddFeed) {
            add_feed(hwnd);
        } else if (id == kRefresh) {
            refresh_all_feeds(hwnd);
        } else if (id == kQueueUp || id == kQueueDown || id == kQueueRemove) {
            const int selected = list_selection(control(hwnd, kQueueList));
            if (selected >= 0 && static_cast<std::size_t>(selected) < g_queue.items().size()) {
                const std::string item = g_queue.items()[selected];
                auto queue = g_queue;
                if (id == kQueueRemove) queue.remove(item);
                else queue.move(item, id == kQueueUp);
                save_queue(hwnd, std::move(queue), item);
            }
        }
        return 0;
    }
    case WM_CLOSE:
        if (g_fetch.joinable()) {
            g_cancel = true;
            g_closing = true;
            ShowWindow(hwnd, SW_HIDE);
            return 0;
        }
        return DefWindowProcW(hwnd, message, wparam, lparam);
    case WM_DESTROY:
        g_cancel = true;
        if (g_fetch.joinable()) g_fetch.join();
        if (g_position_hooked && g_session) g_session.PositionChanged(g_position_token);
        PostQuitMessage(0);
        return 0;
    default:
        return DefWindowProcW(hwnd, message, wparam, lparam);
    }
    return 0;
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

    for (int i = 1; i < argc; ++i)
        if (std::wcscmp(argv[i], L"--smoke-test") == 0) g_smoke = true;
    HWND hwnd = CreateWindowExW(0, kWindowClass, L"podcast · Windows 预览版",
        WS_OVERLAPPEDWINDOW | WS_CLIPCHILDREN, CW_USEDEFAULT, CW_USEDEFAULT,
        1120, 585, nullptr, nullptr, g_instance, nullptr);
    if (!hwnd) return 4;

    if (g_smoke) {
        const bool lists_visible = (GetWindowLongPtrW(control(hwnd, kFeedList), GWL_STYLE) & WS_VISIBLE) &&
            (GetWindowLongPtrW(control(hwnd, kEpisodeList), GWL_STYLE) & WS_VISIBLE) &&
            (GetWindowLongPtrW(control(hwnd, kQueueList), GWL_STYLE) & WS_VISIBLE);
        DestroyWindow(hwnd);
        if (!lists_visible) return 5;
    } else {
        ShowWindow(hwnd, SW_SHOW);
        UpdateWindow(hwnd);
        set_status(L"请加载本地音频或输入 http(s) 地址");
    }

    MSG msg = {};
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        if (!IsDialogMessageW(hwnd, &msg)) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
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
