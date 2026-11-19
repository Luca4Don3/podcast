import AVFoundation
import Foundation
import PodcastKit
import SwiftUI

@main
@MainActor
struct PodcastApp: App {
    @StateObject private var library = LibrarySession()
    @Environment(.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            Group {
                if let accountID = library.accountID {
                    LibraryView(library: library, accountID: accountID)
                        .id(accountID)
                } else {
                    SignInView(library: library)
                }
            }
            .onChange(of: scenePhase) { phase in
                if phase == .background { library.playback.pause() }
            }
        }
    }
}

private enum LoadState<Value> {
    case loading
    case loaded(Value)
    case failed(String)
}

@MainActor
private final class LibrarySession: ObservableObject {
    @Published private(set) var accountID: UUID?
    @Published private(set) var signingIn = false
    @Published private(set) var signInError: String?
    @Published private(set) var subscriptions: LoadState<[PodcastSummary]> = .loading
    @Published private(set) var episodes: LoadState<[PodcastEpisode]> = .loading
    @Published private(set) var selection: String?

    private var credentials: (api: PodcastAPI, token: String)?
    private var loginTask: Task<Void, Never>?
    private var subscriptionsTask: Task<Void, Never>?
    private var episodesTask: Task<Void, Never>?
    private var loginAttempt: UUID?
    private var episodesRequest: UUID?
    let playback = EpisodePlayback()

    var selectedPodcast: PodcastSummary? {
        guard case .loaded(let podcasts) = subscriptions else { return nil }
        return podcasts.first { $0.id == selection }
    }

    func signIn(server: String, email: String, password: String) {
        guard accountID == nil, !signingIn else { return }
        let api: PodcastAPI
        do {
            api = PodcastAPI(baseURL: try PodcastAPI.serverURL(server))
        } catch {
            signInError = apiMessage(error, fallback: "请输入有效的 HTTPS 服务器地址。")
            return
        }
        let attempt = UUID()
        loginAttempt = attempt
        signInError = nil
        signingIn = true
        loginTask = Task { [weak self] in
            do {
                let token = try await api.login(email: email, password: password)
                guard let self, !Task.isCancelled, self.loginAttempt == attempt else { return }
                self.finishSignIn()
                self.credentials = (api, token)
                self.accountID = attempt
                self.loadSubscriptions(accountID: attempt)
            } catch {
                guard let self, !Task.isCancelled, self.loginAttempt == attempt else { return }
                self.finishSignIn()
                self.signInError = apiMessage(error, fallback: "登录失败，请检查账号和网络后重试。")
            }
        }
    }

    func cancelSignIn() {
        guard accountID == nil else { return }
        loginTask?.cancel()
        finishSignIn()
    }

    private func finishSignIn() {
        loginAttempt = nil
        loginTask = nil
        signingIn = false
    }

    func loadSubscriptions(accountID: UUID) {
        guard self.accountID == accountID, let credentials, subscriptionsTask == nil else { return }
        subscriptions = .loading
        subscriptionsTask = Task { [weak self] in
            let result: LoadState<[PodcastSummary]>
            do {
                result = .loaded(try await credentials.api.podcasts(token: credentials.token))
            } catch {
                result = .failed(apiMessage(error, fallback: "无法读取订阅，请重试。"))
            }
            guard let self, !Task.isCancelled, self.accountID == accountID else { return }
            self.subscriptionsTask = nil
            self.subscriptions = result
        }
    }

    func select(_ podcastID: String?, accountID: UUID) {
        guard self.accountID == accountID, podcastID != selection else { return }
        if let podcastID {
            guard case .loaded(let podcasts) = subscriptions,
                  podcasts.contains(where: { $0.id == podcastID }) else { return }
        }
        selection = podcastID
        episodesTask?.cancel()
        episodesTask = nil
        episodesRequest = nil
        episodes = .loading
        if let podcastID { loadEpisodes(podcastID: podcastID, accountID: accountID) }
    }

    func loadEpisodes(podcastID: String, accountID: UUID) {
        guard self.accountID == accountID, selection == podcastID, let credentials else { return }
        episodesTask?.cancel()
        episodes = .loading
        let request = UUID()
        episodesRequest = request
        episodesTask = Task { [weak self] in
            let result: LoadState<[PodcastEpisode]>
            do {
                result = .loaded(try await credentials.api.episodes(podcastID: podcastID, token: credentials.token))
            } catch {
                result = .failed(apiMessage(error, fallback: "无法读取单集，请重试。"))
            }
            guard let self, !Task.isCancelled, self.accountID == accountID,
                  self.selection == podcastID, self.episodesRequest == request else { return }
            self.episodesTask = nil
            self.episodesRequest = nil
            self.episodes = result
        }
    }

    func play(episodeID: String, podcastID: String, accountID: UUID) {
        guard self.accountID == accountID, selection == podcastID,
              case .loaded(let currentEpisodes) = episodes,
              let episode = currentEpisodes.first(where: { $0.id == episodeID }) else { return }
        playback.play(episode)
    }

    func signOut(accountID: UUID) {
        guard self.accountID == accountID else { return }
        self.accountID = nil
        credentials = nil
        subscriptionsTask?.cancel()
        subscriptionsTask = nil
        episodesTask?.cancel()
        episodesTask = nil
        episodesRequest = nil
        selection = nil
        subscriptions = .loading
        episodes = .loading
        signInError = nil
        playback.reset()
    }
}

// Only PodcastKit's deliberately safe LocalizedError descriptions reach the UI.
private func apiMessage(_ error: Error, fallback: String) -> String {
    (error as? LocalizedError)?.errorDescription ?? fallback
}

@MainActor
private struct SignInView: View {
    @ObservedObject var library: LibrarySession
    @State private var server = ""
    @State private var email = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("服务器与账号") {
                    TextField("HTTPS 服务器地址", text: $server, prompt: Text("https://podcast.example.com"))
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("邮箱", text: $email)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("密码", text: $password)
                }
                .disabled(library.signingIn)
                Section {
                    if library.signingIn {
                        ProgressView("正在登录…")
                        Button("取消登录") { library.cancelSignIn() }
                    } else {
                        Button("登录", action: submit)
                            .disabled(server.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                      || email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                      || password.isEmpty)
                    }
                    if let message = library.signInError {
                        Text(message).foregroundStyle(.secondary)
                    }
                }
                DisclosureGroup("首版范围与限制") {
                    Text("使用已有账号。仅查看订阅和接口返回的单集（不翻页）；不支持注册、订阅管理、搜索或导入。")
                    Text("仅播放 AVPlayer 支持的 HTTPS 单集音源，不附加登录令牌。有可用定位范围的点播才能拖动进度；不支持下载、后台播放或进度同步。")
                    Text("密码不保存，访问令牌仅驻留本次登录的内存。到期需重新登录；注销仅退出本机，不撤销服务器令牌。")
                }
            }
            .navigationTitle("Podcast")
        }
    }

    private func submit() {
        let submittedPassword = password
        password = ""
        library.signIn(server: server, email: email.trimmingCharacters(in: .whitespacesAndNewlines),
                       password: submittedPassword)
    }
}

@MainActor
private struct LibraryView: View {
    @ObservedObject var library: LibrarySession
    let accountID: UUID

    var body: some View {
        NavigationSplitView {
            List(selection: Binding(get: { library.selection }, set: { library.select($0, accountID: accountID) })) {
                switch library.subscriptions {
                case .loading:
                    ProgressView("正在读取订阅…")
                case .failed(let message):
                    RetryRow(message: message) { library.loadSubscriptions(accountID: accountID) }
                case .loaded(let podcasts):
                    if podcasts.isEmpty { Text("没有订阅").foregroundStyle(.secondary) }
                    ForEach(podcasts, id: .id) { podcast in
                        NavigationLink(value: podcast.id) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(podcast.title)
                                if !podcast.author.isEmpty {
                                    Text(podcast.author).font(.subheadline).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .listStyle(.sidebar)
            .navigationTitle("订阅")
            .toolbar { signOutButton }
        } detail: {
            if let podcast = library.selectedPodcast {
                List {
                    switch library.episodes {
                    case .loading:
                        ProgressView("正在读取单集…")
                    case .failed(let message):
                        RetryRow(message: message) { library.loadEpisodes(podcastID: podcast.id, accountID: accountID) }
                    case .loaded(let episodes):
                        if episodes.isEmpty { Text("没有单集").foregroundStyle(.secondary) }
                        ForEach(episodes, id: .id) { episode in
                            Button {
                                library.play(episodeID: episode.id, podcastID: podcast.id, accountID: accountID)
                            } label: {
                                Label(episode.title, systemImage: "play.circle")
                            }
                            .disabled(episode.audioUrl.isEmpty)
                        }
                    }
                }
                .navigationTitle(podcast.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { signOutButton }
            } else {
                Text("选择一个订阅以查看单集")
                    .foregroundStyle(.secondary)
                    .navigationTitle("单集")
            }
        }
        .navigationSplitViewStyle(.balanced)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            PlaybackBar(playback: library.playback)
        }
    }

    private var signOutButton: some ToolbarContent {
        ToolbarItem(placement: .navigationBarTrailing) {
            Button("注销") { library.signOut(accountID: accountID) }
        }
    }
}

@MainActor
private struct RetryRow: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(message).foregroundStyle(.secondary)
            Button("重试", action: retry)
        }
    }
}

@MainActor
private final class EpisodePlayback: ObservableObject {
    private let player = AVPlayer()
    private var playerStatus: NSKeyValueObservation?
    private var itemStatus: NSKeyValueObservation?
    private var rangesStatus: NSKeyValueObservation?
    private var positionObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var failureObserver: NSObjectProtocol?
    private var audioSessionActive = false

    @Published private(set) var itemID = UUID()
    @Published private(set) var title: String?
    @Published private(set) var status = ""
    @Published private(set) var wantsPlayback = false
    @Published private(set) var canPlay = false
    @Published private(set) var position = 0.0
    @Published private(set) var seekRange: ClosedRange<Double>?
    private var ended = false

    func play(_ episode: PodcastEpisode) {
        reset()
        title = episode.title
        guard let components = URLComponents(string: episode.audioUrl),
              components.scheme?.lowercased() == "https", let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil, let url = components.url else {
            status = "该单集没有可播放的 HTTPS 音频地址。"
            return
        }
        let item = AVPlayerItem(url: url)
        let id = itemID
        player.replaceCurrentItem(with: item)
        canPlay = true
        // Callbacks may already be queued when an episode or account changes.
        playerStatus = player.observe(.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Task { @MainActor [weak self] in self?.update(id: id) }
        }
        itemStatus = item.observe(.status, options: [.new]) { [weak self] _, _ in
            Task { @MainActor [weak self] in self?.update(id: id) }
        }
        rangesStatus = item.observe(.seekableTimeRanges, options: [.new]) { [weak self] _, _ in
            Task { @MainActor [weak self] in self?.update(id: id) }
        }
        positionObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
                                                          queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in self?.update(id: id) }
        }
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification,
                                                              object: item, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.itemID == id, self.canPlay else { return }
                self.ended = true
                self.pause()
                self.update(id: id)
            }
        }
        failureObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.failedToPlayToEndTimeNotification,
                                                                  object: item, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.itemID == id else { return }
                self.fail()
            }
        }
        resume()
    }

    func toggle(id: UUID) {
        guard id == itemID, canPlay else { return }
        if wantsPlayback { pause() } else { resume() }
    }

    func pause() {
        guard wantsPlayback else { return }
        player.pause()
        wantsPlayback = false
        deactivateAudioSession()
        update(id: itemID)
    }

    func seek(to seconds: Double, id: UUID) {
        guard id == itemID, canPlay, let seekRange, seekRange.contains(seconds) else { return }
        ended = false
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
    }

    func reset() {
        itemID = UUID()
        playerStatus = nil
        itemStatus = nil
        rangesStatus = nil
        if let positionObserver { player.removeTimeObserver(positionObserver) }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        if let failureObserver { NotificationCenter.default.removeObserver(failureObserver) }
        positionObserver = nil
        endObserver = nil
        failureObserver = nil
        player.pause()
        player.currentItem?.cancelPendingSeeks()
        player.replaceCurrentItem(with: nil)
        deactivateAudioSession()
        title = nil
        status = ""
        wantsPlayback = false
        canPlay = false
        ended = false
        position = 0
        seekRange = nil
    }

    private func resume() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback)
            try session.setActive(true)
            audioSessionActive = true
            if ended {
                ended = false
                player.seek(to: .zero)
            }
            wantsPlayback = true
            player.play()
            update(id: itemID)
        } catch {
            fail()
        }
    }

    private func deactivateAudioSession() {
        guard audioSessionActive else { return }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        audioSessionActive = false
    }

    private func fail() {
        canPlay = false
        wantsPlayback = false
        player.pause()
        deactivateAudioSession()
        seekRange = nil
        status = "无法播放。请重新点选单集重试。"
    }

    private func update(id: UUID) {
        guard itemID == id, canPlay, let item = player.currentItem else { return }
        if item.status == .failed { fail(); return }

        let elapsed = player.currentTime().seconds
        if elapsed.isFinite, elapsed >= 0, position != elapsed { position = elapsed }
        let duration = item.duration.seconds
        var range: ClosedRange<Double>?
        if item.status == .readyToPlay, duration.isFinite, duration > 0 {
            let available = item.seekableTimeRanges.compactMap { value -> ClosedRange<Double>? in
                let timeRange = value.timeRangeValue
                let start = max(timeRange.start.seconds, 0)
                let end = min(timeRange.end.seconds, duration)
                return start.isFinite && end.isFinite && end > start ? start...end : nil
            }
            range = available.first(where: { $0.contains(position) }) ?? available.first
        }
        if seekRange != range { seekRange = range }
        let nextStatus: String
        if ended {
            nextStatus = "已播完"
        } else if !wantsPlayback {
            nextStatus = "已暂停"
        } else if player.timeControlStatus == .playing {
            nextStatus = "播放中"
        } else {
            nextStatus = "正在载入音频…"
        }
        if status != nextStatus { status = nextStatus }
    }
}

@MainActor
private struct PlaybackBar: View {
    @ObservedObject var playback: EpisodePlayback

    var body: some View {
        if let title = playback.title {
            controls(title: title, itemID: playback.itemID)
                .id(playback.itemID)
        }
    }

    private func controls(title: String, itemID: UUID) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                VStack(alignment: .leading) {
                    Text(title).font(.headline).lineLimit(2)
                    Text(playback.status).font(.footnote).foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                if playback.canPlay {
                    Button {
                        playback.toggle(id: itemID)
                    } label: {
                        Image(systemName: playback.wantsPlayback ? "pause.fill" : "play.fill")
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityLabel(playback.wantsPlayback ? Text("暂停") : Text("播放"))
                }
            }
            if let range = playback.seekRange {
                SeekSlider(playback: playback, itemID: itemID, range: range)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
        .background(.regularMaterial)
    }
}

@MainActor
private struct SeekSlider: View {
    @ObservedObject var playback: EpisodePlayback
    let itemID: UUID
    let range: ClosedRange<Double>
    @State private var dragPosition: Double?

    private var displayedPosition: Double {
        min(max(dragPosition ?? playback.position, range.lowerBound), range.upperBound)
    }

    var body: some View {
        Slider(value: Binding(get: { displayedPosition }, set: { value in
            if dragPosition != nil { dragPosition = value } else { playback.seek(to: value, id: itemID) }
        }), in: range, onEditingChanged: { editing in
            if editing {
                dragPosition = displayedPosition
            } else if dragPosition != nil {
                let target = displayedPosition
                dragPosition = nil
                playback.seek(to: target, id: itemID)
            }
        }) {
            Text("播放进度")
        }
        .accessibilityValue(Text(Duration.seconds(displayedPosition).formatted(.time(pattern: .minuteSecond))))
    }
}
