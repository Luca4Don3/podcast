#import "FeedFetch.h"
#import "Feed.h"

@interface PDFeedFetch () <NSURLSessionDataDelegate>
@end

@implementation PDFeedFetch {
    NSURLSession *_session;
    NSMutableData *_body;
    PDFeedCompletion _completion;
    NSString *_responseError;
    NSUInteger _redirects;
}
- (void)startURL:(NSURL *)url completion:(PDFeedCompletion)completion {
    _completion = [completion copy];
    _body = [NSMutableData data];
    NSURLSessionConfiguration *settings = NSURLSessionConfiguration.ephemeralSessionConfiguration;
    settings.URLCache = nil;
    settings.URLCredentialStorage = nil;
    settings.HTTPShouldSetCookies = NO;
    settings.timeoutIntervalForRequest = 12;
    settings.timeoutIntervalForResource = 20;
    _session = [NSURLSession sessionWithConfiguration:settings delegate:self delegateQueue:NSOperationQueue.mainQueue];
    [[_session dataTaskWithURL:url] resume];
}
- (void)cancel {
    _completion = nil;
    [_session invalidateAndCancel];
    _session = nil;
}
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task
    willPerformHTTPRedirection:(NSHTTPURLResponse *)response newRequest:(NSURLRequest *)request
    completionHandler:(void (^)(NSURLRequest *))reply {
    (void)session; (void)task;
    NSURL *target = PDWebURL(request.URL.absoluteString, nil);
    BOOL downgrade = [response.URL.scheme.lowercaseString isEqualToString:@"https"] &&
                     [target.scheme.lowercaseString isEqualToString:@"http"];
    if (!target || downgrade || ++_redirects > 8) {
        _responseError = @"Feed redirect not allowed.";
        reply(nil);
    } else reply(request);
}
- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)task
    didReceiveResponse:(NSURLResponse *)response completionHandler:(void (^)(NSURLSessionResponseDisposition))reply {
    (void)session; (void)task;
    NSInteger status = [response isKindOfClass:NSHTTPURLResponse.class] ? ((NSHTTPURLResponse *)response).statusCode : 0;
    if (!_responseError && (status < 200 || status >= 300))
        _responseError = [NSString stringWithFormat:@"Feed request failed (HTTP %ld).", (long)status];
    if (!_responseError && response.expectedContentLength > (long long)PDMaxFeedBytes)
        _responseError = @"Feed exceeds 4 MiB.";
    reply(_responseError ? NSURLSessionResponseCancel : NSURLSessionResponseAllow);
}
- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)task didReceiveData:(NSData *)data {
    (void)session;
    if (data.length > PDMaxFeedBytes - _body.length) {
        _responseError = @"Feed exceeds 4 MiB.";
        [task cancel];
    } else if (!_responseError) [_body appendData:data];
}
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    PDFeedCompletion callback = _completion;
    _completion = nil;
    _session = nil;
    [session finishTasksAndInvalidate];
    NSString *detail = _responseError ?: error.localizedDescription;
    if (callback) callback(detail ? nil : _body, task.response.URL, detail);
}
@end
