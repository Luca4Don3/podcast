#import "Smoke.h"
#import "Feed.h"
#import <AVFoundation/AVFoundation.h>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>

namespace {
// One second of mono silence, PCM 16 bit at 8000 Hz (WAV).
constexpr unsigned char waveHeader[] = {
    'R','I','F','F', 0xa4,0x3e,0,0, 'W','A','V','E', 'f','m','t',' ',
    16,0,0,0, 1,0, 1,0, 0x40,0x1f,0,0, 0x80,0x3e,0,0, 2,0, 16,0,
    'd','a','t','a', 0x80,0x3e,0,0
};
int fail(const char *what, NSError *error = nil) {
    std::fprintf(stderr, "macos smoke failed: %s%s%s\n", what, error ? ": " : "", error ? error.localizedDescription.UTF8String : "");
    return 1;
}
int checkAudio(NSURL *url) {
    std::array<unsigned char, 16000> silence{};
    NSMutableData *wave = [NSMutableData dataWithBytes:waveHeader length:sizeof(waveHeader)];
    [wave appendBytes:silence.data() length:silence.size()];
    NSError *error = nil;
    if (![wave writeToURL:url options:NSDataWritingAtomic error:&error]) return fail("write WAV fixture", error);
    AVPlayerItem *item = [AVPlayerItem playerItemWithURL:url];
    AVPlayer *player = [AVPlayer playerWithPlayerItem:item];
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(7);
    while (item.status == AVPlayerItemStatusUnknown && std::chrono::steady_clock::now() < deadline)
        [NSRunLoop.currentRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
    if (item.status != AVPlayerItemStatusReadyToPlay) return fail("AVPlayer did not become ready", item.error);
    const double seconds = CMTimeGetSeconds(item.duration);
    if (!std::isfinite(seconds) || std::abs(seconds - 1.0) > 0.01) return fail("unexpected local duration");
    __block BOOL completed = NO;
    __block BOOL succeeded = NO;
    [player seekToTime:CMTimeMake(1, 2) toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero completionHandler:^(BOOL finished) {
        dispatch_async(dispatch_get_main_queue(), ^{ succeeded = finished; completed = YES; });
    }];
    const auto seekDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (!completed && std::chrono::steady_clock::now() < seekDeadline)
        [NSRunLoop.currentRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
    [player replaceCurrentItemWithPlayerItem:nil];
    return completed && succeeded ? 0 : fail("AVPlayer did not complete a local seek");
}
}

int PDRunSmokeTest() {
    NSData *feed = [@"<rss><channel><title>Smoke</title><item><enclosure url='https://example.test/a.mp3' type='audio/mpeg'/></item></channel></rss>" dataUsingEncoding:NSUTF8StringEncoding];
    if (PDParseImport(feed, nil).episodes.count != 1) return fail("RSS/shared episode planner");
    NSURL *temporary = [[[NSURL fileURLWithPath:NSTemporaryDirectory() isDirectory:YES]
        URLByAppendingPathComponent:[@"podcast-smoke-" stringByAppendingString:NSUUID.UUID.UUIDString] isDirectory:YES] copy];
    NSError *error = nil;
    if (![NSFileManager.defaultManager createDirectoryAtURL:temporary withIntermediateDirectories:NO
            attributes:@{NSFilePosixPermissions: @0700} error:&error]) return fail("create scratch directory", error);
    int result = checkAudio([temporary URLByAppendingPathComponent:@"fixture.wav"]);
    if (![NSFileManager.defaultManager removeItemAtURL:temporary error:&error] && !result) return fail("clean scratch directory", error);
    if (!result) std::puts("macos smoke ok (offline RSS and AVPlayer WAV/seek)");
    return result;
}
