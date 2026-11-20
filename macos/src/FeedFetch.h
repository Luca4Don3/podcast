#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^PDFeedCompletion)(NSData * _Nullable data, NSURL * _Nullable finalURL, NSString * _Nullable error);

// Single-use, bounded public feed request. Start and cancel on the main thread.
@interface PDFeedFetch : NSObject
- (void)startURL:(NSURL *)url completion:(PDFeedCompletion)completion;
- (void)cancel;
@end

NS_ASSUME_NONNULL_END
