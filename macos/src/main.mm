#import <AppKit/AppKit.h>
#import <AVFoundation/AVFoundation.h>
#import "Feed.h"
#import "FeedFetch.h"
#import "Smoke.h"
#include <algorithm>
#include <cmath>
#include <cstring>

static NSButton *button(NSString *title, id target, SEL action) {
    return [NSButton buttonWithTitle:title target:target action:action];
}

static NSStackView *row(NSArray<NSView *> *views) {
    NSStackView *stack = [NSStackView stackViewWithViews:views];
    stack.orientation = NSUserInterfaceLayoutOrientationHorizontal;
    stack.alignment = NSLayoutAttributeCenterY;
    stack.spacing = 8;
    return stack;
}

static NSString *clockText(double seconds) {
    if (!std::isfinite(seconds) || seconds < 0 || seconds > 31557600000.0) return @"--:--";
    auto time = static_cast<long long>(seconds);
    if (time >= 3600) return [NSString stringWithFormat:@"%lld:%02lld:%02lld", time / 3600, (time / 60) % 60, time % 60];
    return [NSString stringWithFormat:@"%lld:%02lld", time / 60, time % 60];
}

@interface PDApp : NSObject <NSApplicationDelegate, NSTableViewDataSource, NSTableViewDelegate>
@end

@implementation PDApp {
    NSWindow *_window;
    NSTextField *_input, *_importStatus, *_now, *_playStatus, *_clock;
    NSTableView *_table;
    NSButton *_play, *_playSelected, *_cancel;
    NSSlider *_seek;
    AVPlayer *_player;
    NSTimer *_ticker;
    BOOL _wantsPlay, _ended, _seeking, _localAudio;
    NSUInteger _seekGeneration;
    NSString *_playFailure;
    NSMutableArray<PDEpisode *> *_episodes;
    NSMutableArray<NSString *> *_feedTitles;
    NSMutableSet<NSString *> *_episodeKeys;
    PDFeedFetch *_fetch;
    NSArray<NSURL *> *_opml;
    NSUInteger _generation, _nextFeed, _importedFeeds, _failedFeeds;
}

- (void)applicationDidFinishLaunching:(NSNotification *)notification {
    (void)notification;
    _episodes = [NSMutableArray array];
    _feedTitles = [NSMutableArray array];
    _episodeKeys = [NSMutableSet set];
    _player = [AVPlayer new];
    [self makeMenu];
    [self makeWindow];
    NSNotificationCenter *center = NSNotificationCenter.defaultCenter;
    [center addObserver:self selector:@selector(didEnd:) name:AVPlayerItemDidPlayToEndTimeNotification object:nil];
    [center addObserver:self selector:@selector(didFail:) name:AVPlayerItemFailedToPlayToEndTimeNotification object:nil];
    _ticker = [NSTimer scheduledTimerWithTimeInterval:0.2 target:self selector:@selector(tick:) userInfo:nil repeats:YES];
    [_window makeKeyAndOrderFront:nil];
    [NSApp activateIgnoringOtherApps:YES];
}

- (void)makeMenu {
    NSMenu *bar = [NSMenu new];
    NSMenuItem *app = [NSMenuItem new];
    [bar addItem:app];
    app.submenu = [NSMenu new];
    [app.submenu addItemWithTitle:@"Quit podcast" action:@selector(terminate:) keyEquivalent:@"q"];
    NSMenuItem *file = [[NSMenuItem alloc] initWithTitle:@"File" action:nil keyEquivalent:@""];
    [bar addItem:file];
    file.submenu = [[NSMenu alloc] initWithTitle:@"File"];
    [file.submenu addItemWithTitle:@"Open Audio File…" action:@selector(openAudio:) keyEquivalent:@"o"].target = self;
    [file.submenu addItemWithTitle:@"Import Feed or OPML File…" action:@selector(openFeed:) keyEquivalent:@"i"].target = self;
    NSMenuItem *edit = [[NSMenuItem alloc] initWithTitle:@"Edit" action:nil keyEquivalent:@""];
    [bar addItem:edit];
    edit.submenu = [[NSMenu alloc] initWithTitle:@"Edit"];
    [edit.submenu addItemWithTitle:@"Cut" action:@selector(cut:) keyEquivalent:@"x"];
    [edit.submenu addItemWithTitle:@"Copy" action:@selector(copy:) keyEquivalent:@"c"];
    [edit.submenu addItemWithTitle:@"Paste" action:@selector(paste:) keyEquivalent:@"v"];
    [edit.submenu addItemWithTitle:@"Select All" action:@selector(selectAll:) keyEquivalent:@"a"];
    NSApp.mainMenu = bar;
}

- (void)makeWindow {
    _window = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, 850, 520)
        styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable
        backing:NSBackingStoreBuffered defer:NO];
    _window.title = @"podcast";
    _window.contentMinSize = NSMakeSize(710, 380);
    [_window center];

    _input = [NSTextField textFieldWithString:@""];
    _input.placeholderString = @"https://… (audio, RSS, Atom, or OPML)";
    _input.accessibilityLabel = @"Audio or feed URL";
    [_input setContentHuggingPriority:NSLayoutPriorityDefaultLow forOrientation:NSLayoutConstraintOrientationHorizontal];
    [_input.widthAnchor constraintGreaterThanOrEqualToConstant:180].active = YES;
    NSStackView *urlRow = row(@[_input, button(@"Play URL", self, @selector(playURL:)), button(@"Import URL", self, @selector(importURL:))]);
    _cancel = button(@"Cancel Import", self, @selector(cancelImport:));
    _cancel.enabled = NO;
    _playSelected = button(@"Play Selected", self, @selector(playSelected:));
    _playSelected.enabled = NO;
    NSStackView *fileRow = row(@[button(@"Open Audio File…", self, @selector(openAudio:)),
        button(@"Import XML File…", self, @selector(openFeed:)), _cancel, _playSelected]);
    _importStatus = [NSTextField labelWithString:@"Import an RSS, Atom or OPML document to list episodes."];
    _importStatus.lineBreakMode = NSLineBreakByTruncatingTail;

    _table = [[NSTableView alloc] initWithFrame:NSZeroRect];
    _table.accessibilityLabel = @"Imported episodes";
    _table.dataSource = self;
    _table.delegate = self;
    _table.target = self;
    _table.doubleAction = @selector(playClicked:);
    _table.usesAlternatingRowBackgroundColors = YES;
    _table.columnAutoresizingStyle = NSTableViewLastColumnOnlyAutoresizingStyle;
    NSTableColumn *feed = [[NSTableColumn alloc] initWithIdentifier:@"Feed"];
    feed.title = @"Feed";
    feed.width = 200;
    [_table addTableColumn:feed];
    NSTableColumn *episode = [[NSTableColumn alloc] initWithIdentifier:@"Episode"];
    episode.title = @"Episode";
    [_table addTableColumn:episode];
    NSScrollView *list = [NSScrollView new];
    list.hasVerticalScroller = YES;
    list.autohidesScrollers = YES;
    list.borderType = NSBezelBorder;
    list.documentView = _table;
    [list.heightAnchor constraintGreaterThanOrEqualToConstant:100].active = YES;

    _now = [NSTextField labelWithString:@"Nothing playing"];
    _now.font = [NSFont boldSystemFontOfSize:NSFont.systemFontSize];
    _now.lineBreakMode = NSLineBreakByTruncatingTail;
    _playStatus = [NSTextField labelWithString:@"Open a file, enter an audio URL, or select an episode."];
    _playStatus.lineBreakMode = NSLineBreakByTruncatingTail;
    _play = button(@"Play", self, @selector(togglePlayback:));
    _play.enabled = NO;
    [_play.widthAnchor constraintEqualToConstant:80].active = YES;
    _seek = [NSSlider sliderWithValue:0 minValue:0 maxValue:1 target:self action:@selector(seek:)];
    _seek.continuous = NO;
    _seek.enabled = NO;
    _seek.accessibilityLabel = @"Playback position";
    _clock = [NSTextField labelWithString:@"--:-- / --:--"];
    _clock.alignment = NSTextAlignmentRight;
    _clock.font = [NSFont monospacedDigitSystemFontOfSize:NSFont.systemFontSize weight:NSFontWeightRegular];
    [_clock.widthAnchor constraintGreaterThanOrEqualToConstant:135].active = YES;
    [_clock setContentHuggingPriority:NSLayoutPriorityRequired forOrientation:NSLayoutConstraintOrientationHorizontal];
    NSStackView *transport = row(@[_play, _seek, _clock]);
    NSStackView *layout = [NSStackView stackViewWithViews:@[urlRow, fileRow, _importStatus, list, _now, transport, _playStatus]];
    layout.orientation = NSUserInterfaceLayoutOrientationVertical;
    layout.alignment = NSLayoutAttributeLeading;
    layout.spacing = 10;
    layout.translatesAutoresizingMaskIntoConstraints = NO;
    NSView *content = _window.contentView;
    [content addSubview:layout];
    [NSLayoutConstraint activateConstraints:@[
        [layout.leadingAnchor constraintEqualToAnchor:content.leadingAnchor constant:16],
        [layout.trailingAnchor constraintEqualToAnchor:content.trailingAnchor constant:-16],
        [layout.topAnchor constraintEqualToAnchor:content.topAnchor constant:16],
        [layout.bottomAnchor constraintEqualToAnchor:content.bottomAnchor constant:-16],
        [urlRow.widthAnchor constraintEqualToAnchor:layout.widthAnchor],
        [_importStatus.widthAnchor constraintEqualToAnchor:layout.widthAnchor],
        [list.widthAnchor constraintEqualToAnchor:layout.widthAnchor],
        [_now.widthAnchor constraintEqualToAnchor:layout.widthAnchor],
        [transport.widthAnchor constraintEqualToAnchor:layout.widthAnchor],
        [_playStatus.widthAnchor constraintEqualToAnchor:layout.widthAnchor],
    ]];
}

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)sender { (void)sender; return YES; }
- (void)applicationWillTerminate:(NSNotification *)notification {
    (void)notification;
    [_ticker invalidate];
    [_fetch cancel];
    [_player pause];
    [NSNotificationCenter.defaultCenter removeObserver:self];
}

- (NSInteger)numberOfRowsInTableView:(NSTableView *)tableView { (void)tableView; return _episodes.count; }
- (NSView *)tableView:(NSTableView *)table viewForTableColumn:(NSTableColumn *)column row:(NSInteger)index {
    NSTextField *label = [table makeViewWithIdentifier:column.identifier owner:self];
    if (!label) {
        label = [NSTextField labelWithString:@""];
        label.identifier = column.identifier;
        label.lineBreakMode = NSLineBreakByTruncatingTail;
    }
    label.stringValue = [column.identifier isEqualToString:@"Feed"] ? _feedTitles[index] : _episodes[index].title;
    return label;
}
- (void)tableViewSelectionDidChange:(NSNotification *)notification {
    (void)notification;
    _playSelected.enabled = _table.selectedRow >= 0;
}
- (void)playRow:(NSInteger)index {
    if (index < 0 || static_cast<NSUInteger>(index) >= _episodes.count) return;
    PDEpisode *episode = _episodes[index];
    [self loadAudio:episode.url title:episode.title];
}
- (void)playClicked:(id)sender { (void)sender; [self playRow:_table.clickedRow]; }
- (void)playSelected:(id)sender { (void)sender; [self playRow:_table.selectedRow]; }

- (void)openAudio:(id)sender {
    (void)sender;
    NSOpenPanel *panel = NSOpenPanel.openPanel;
    panel.message = @"Choose an audio file supported by macOS.";
    panel.canChooseDirectories = NO;
    [panel beginSheetModalForWindow:_window completionHandler:^(NSModalResponse result) {
        if (result == NSModalResponseOK && panel.URL)
            [self loadAudio:panel.URL title:panel.URL.lastPathComponent];
    }];
}
- (void)playURL:(id)sender {
    (void)sender;
    NSURL *url = PDWebURL(_input.stringValue, nil);
    if (url) [self loadAudio:url title:url.lastPathComponent.length ? url.lastPathComponent : url.host];
    else _playStatus.stringValue = @"Enter an HTTP(S) audio URL without embedded credentials.";
}
- (void)loadAudio:(NSURL *)url title:(NSString *)title {
    _now.stringValue = title.length ? title : @"Audio";
    _localAudio = url.isFileURL;
    ++_seekGeneration;
    _ended = _seeking = NO;
    _playFailure = nil;
    [_player pause];
    [_player replaceCurrentItemWithPlayerItem:[AVPlayerItem playerItemWithURL:url]];
    _wantsPlay = YES;
    [_player play];
    [self tick:nil];
}
- (void)didEnd:(NSNotification *)note {
    AVPlayerItem *item = note.object;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (item != self->_player.currentItem) return;
        self->_ended = YES;
        self->_wantsPlay = NO;
        [self tick:nil];
    });
}
- (void)didFail:(NSNotification *)note {
    AVPlayerItem *item = note.object;
    NSError *error = note.userInfo[AVPlayerItemFailedToPlayToEndTimeErrorKey];
    dispatch_async(dispatch_get_main_queue(), ^{
        if (item != self->_player.currentItem) return;
        self->_playFailure = error.localizedDescription ?: @"Unable to play this source.";
        self->_wantsPlay = NO;
        [self->_player pause];
        [self tick:nil];
    });
}
- (void)togglePlayback:(id)sender {
    (void)sender;
    if (!_player.currentItem || _playFailure || _player.currentItem.status == AVPlayerItemStatusFailed) return;
    _wantsPlay = !_wantsPlay;
    if (_wantsPlay) {
        if (_ended) {
            _ended = NO;
            CMTimeRange available = [self seekRange:_player.currentItem];
            [_player seekToTime:CMTIMERANGE_IS_VALID(available) ? available.start : kCMTimeZero];
        }
        [_player play];
    } else [_player pause];
    [self tick:nil];
}
- (CMTimeRange)seekRange:(AVPlayerItem *)item {
    if (item.status != AVPlayerItemStatusReadyToPlay) return kCMTimeRangeInvalid;
    CMTimeRange fallback = kCMTimeRangeInvalid;
    CMTime time = item.currentTime;
    for (NSValue *value in item.seekableTimeRanges) {
        CMTimeRange range = value.CMTimeRangeValue;
        double start = CMTimeGetSeconds(range.start), end = CMTimeGetSeconds(CMTimeRangeGetEnd(range));
        if (!std::isfinite(start) || !std::isfinite(end) || end <= start) continue;
        if (CMTimeRangeContainsTime(range, time)) return range;
        fallback = range;
    }
    if (_localAudio && !CMTIMERANGE_IS_VALID(fallback) && std::isfinite(CMTimeGetSeconds(item.duration)) &&
        CMTimeCompare(item.duration, kCMTimeZero) > 0) return CMTimeRangeMake(kCMTimeZero, item.duration);
    return fallback;
}
- (void)seek:(id)sender {
    (void)sender;
    AVPlayerItem *item = _player.currentItem;
    if (!item || _playFailure) return;
    CMTimeRange range = [self seekRange:item];
    if (!CMTIMERANGE_IS_VALID(range)) return;
    double start = CMTimeGetSeconds(range.start), end = CMTimeGetSeconds(CMTimeRangeGetEnd(range));
    _ended = NO;
    _seeking = YES;
    NSUInteger generation = ++_seekGeneration;
    [_player seekToTime:CMTimeMakeWithSeconds(std::clamp(_seek.doubleValue, start, end), 600)
        completionHandler:^(BOOL finished) {
        (void)finished;
        dispatch_async(dispatch_get_main_queue(), ^{
            if (self->_seekGeneration != generation) return;
            self->_seeking = NO;
            [self tick:nil];
        });
    }];
}
- (void)tick:(NSTimer *)timer {
    (void)timer;
    AVPlayerItem *item = _player.currentItem;
    if (!item) return;
    BOOL failed = _playFailure || item.status == AVPlayerItemStatusFailed;
    _play.enabled = !failed;
    _play.title = _wantsPlay && !failed ? @"Pause" : @"Play";
    if (failed) _playStatus.stringValue = _playFailure ?: item.error.localizedDescription ?: @"Unable to play this source.";
    else if (_ended) _playStatus.stringValue = @"Finished.";
    else if (!_wantsPlay) _playStatus.stringValue = @"Paused.";
    else if (item.status == AVPlayerItemStatusUnknown) _playStatus.stringValue = @"Opening audio…";
    else if (_player.timeControlStatus == AVPlayerTimeControlStatusPlaying) _playStatus.stringValue = @"Playing.";
    else _playStatus.stringValue = @"Buffering…";
    CMTimeRange range = [self seekRange:item];
    double time = CMTimeGetSeconds(item.currentTime);
    _seek.enabled = !failed && CMTIMERANGE_IS_VALID(range);
    if (_seek.enabled) {
        double start = CMTimeGetSeconds(range.start), end = CMTimeGetSeconds(CMTimeRangeGetEnd(range));
        if (!_seeking) {
            _seek.minValue = start;
            _seek.maxValue = end;
            _seek.doubleValue = std::isfinite(time) ? std::clamp(time, start, end) : start;
        }
    } else _seek.doubleValue = _seek.minValue;
    _clock.stringValue = [NSString stringWithFormat:@"%@ / %@", clockText(time), clockText(CMTimeGetSeconds(item.duration))];
}

- (void)cancelImport:(id)sender {
    ++_generation;
    [_fetch cancel];
    _fetch = nil;
    _opml = nil;
    _cancel.enabled = NO;
    if (sender) _importStatus.stringValue = @"Import canceled; previously imported episodes were kept.";
}
- (void)openFeed:(id)sender {
    (void)sender;
    NSOpenPanel *panel = NSOpenPanel.openPanel;
    panel.message = @"Choose an RSS, Atom, or OPML XML file.";
    panel.canChooseDirectories = NO;
    [panel beginSheetModalForWindow:_window completionHandler:^(NSModalResponse result) {
        if (result != NSModalResponseOK || !panel.URL) return;
        [self cancelImport:nil];
        NSURL *file = panel.URL;
        NSUInteger generation = self->_generation;
        self->_cancel.enabled = YES;
        self->_importStatus.stringValue = @"Reading XML file…";
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
            NSNumber *regular = nil;
            [file getResourceValue:&regular forKey:NSURLIsRegularFileKey error:nil];
            NSFileHandle *handle = regular.boolValue ? [NSFileHandle fileHandleForReadingFromURL:file error:nil] : nil;
            NSData *data = [handle readDataUpToLength:PDMaxFeedBytes + 1 error:nil];
            [handle closeFile];
            PDImport *parsed = data ? PDParseImport(data, nil) : nil;
            dispatch_async(dispatch_get_main_queue(), ^{
                if (generation != self->_generation) return;
                [self receiveImport:parsed source:file error:data.length > PDMaxFeedBytes ? @"File exceeds 4 MiB." :
                    (data ? nil : @"Unable to read this file.")];
            });
        });
    }];
}
- (void)importURL:(id)sender {
    (void)sender;
    NSURL *url = PDWebURL(_input.stringValue, nil);
    if (!url) {
        if (!_cancel.enabled) _importStatus.stringValue = @"Enter an HTTP(S) feed URL without embedded credentials.";
        return;
    }
    [self cancelImport:nil];
    _cancel.enabled = YES;
    _importStatus.stringValue = @"Downloading feed…";
    [self fetch:url];
}
- (void)fetch:(NSURL *)url {
    NSUInteger generation = _generation;
    __weak PDApp *weakSelf = self;
    _fetch = [PDFeedFetch new];
    [_fetch startURL:url completion:^(NSData *data, NSURL *finalURL, NSString *error) {
        PDApp *app = weakSelf;
        if (!app || app->_generation != generation) return;
        app->_fetch = nil;
        if (!data) { [app receiveImport:nil source:url error:error]; return; }
        NSURL *source = finalURL ?: url;
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
            PDImport *parsed = PDParseImport(data, source);
            dispatch_async(dispatch_get_main_queue(), ^{
                if (app->_generation == generation) [app receiveImport:parsed source:source error:nil];
            });
        });
    }];
}
- (NSUInteger)appendEpisodes:(PDImport *)parsed source:(NSURL *)source {
    NSUInteger added = 0;
    for (PDEpisode *episode in parsed.episodes) {
        // Reimporting the same source does not add duplicate rows for unchanged enclosure URLs.
        NSString *key = [NSString stringWithFormat:@"%@\n%@", source.absoluteString, episode.url.absoluteString];
        if ([_episodeKeys containsObject:key]) continue;
        [_episodeKeys addObject:key];
        [_episodes addObject:episode];
        [_feedTitles addObject:parsed.title];
        ++added;
    }
    if (added) [_table reloadData];
    return added;
}
- (void)receiveImport:(PDImport *)parsed source:(NSURL *)source error:(NSString *)error {
    if (_opml) {
        if (!parsed || parsed.isOPML || !parsed.episodes.count) ++_failedFeeds;
        else { [self appendEpisodes:parsed source:source]; ++_importedFeeds; }
        [self importNext];
    } else if (parsed.isOPML && parsed.feeds.count) {
        _opml = parsed.feeds;
        _nextFeed = _importedFeeds = _failedFeeds = 0;
        [self importNext];
    } else {
        _cancel.enabled = NO;
        if (!parsed) _importStatus.stringValue = error ?: @"Invalid, oversized or unsupported RSS, Atom or OPML document.";
        else if (parsed.isOPML) _importStatus.stringValue = @"This OPML has no usable HTTP(S) feeds.";
        else if (!parsed.episodes.count) _importStatus.stringValue = @"No usable audio enclosures were found.";
        else _importStatus.stringValue = [NSString stringWithFormat:@"%@: %lu new episode(s).", parsed.title, (unsigned long)[self appendEpisodes:parsed source:source]];
    }
}
- (void)importNext {
    if (_nextFeed == _opml.count) {
        _opml = nil;
        _cancel.enabled = NO;
        _importStatus.stringValue = [NSString stringWithFormat:@"OPML: %lu feed(s) imported, %lu failed or empty.",
            (unsigned long)_importedFeeds, (unsigned long)_failedFeeds];
        return;
    }
    NSURL *url = _opml[_nextFeed++];
    _importStatus.stringValue = [NSString stringWithFormat:@"Importing feed %lu of %lu…", (unsigned long)_nextFeed, (unsigned long)_opml.count];
    [self fetch:url];
}
@end

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc == 2 && std::strcmp(argv[1], "--smoke-test") == 0) return PDRunSmokeTest();
        NSApplication *app = NSApplication.sharedApplication;
        [app setActivationPolicy:NSApplicationActivationPolicyRegular];
        PDApp *delegate = [PDApp new];
        app.delegate = delegate;
        [app run];
        app.delegate = nil;
    }
    return 0;
}
