#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

constexpr NSUInteger PDMaxFeedBytes = 4 * 1024 * 1024;

@interface PDEpisode : NSObject
@property(nonatomic, readonly) NSString *title;
@property(nonatomic, readonly) NSURL *url;
- (instancetype)initWithTitle:(NSString *)title URL:(NSURL *)url;
@end

@interface PDImport : NSObject
@property(nonatomic, readonly) NSString *title;
@property(nonatomic, readonly) NSArray<PDEpisode *> *episodes;
@property(nonatomic, readonly) NSArray<NSURL *> *feeds;
@property(nonatomic, readonly, getter=isOPML) BOOL OPML;
@end

// Accept only HTTP(S) URLs with a hostname and without embedded user credentials.
NSURL * _Nullable PDWebURL(NSString *text, NSURL * _Nullable base);
// Parse a bounded RSS, Atom or OPML document without loading external entities.
PDImport * _Nullable PDParseImport(NSData *data, NSURL * _Nullable base);

NS_ASSUME_NONNULL_END
