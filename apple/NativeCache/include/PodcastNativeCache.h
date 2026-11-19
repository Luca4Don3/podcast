#ifndef PODCAST_NATIVE_CACHE_H
#define PODCAST_NATIVE_CACHE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Policy: 0 (none), 5, 10, -1 (all). Callers supply episodes newest first. */
int32_t podcast_cache_target_count(int32_t policy, int32_t available);
/* Checks a bounded file prefix for a supported media/container signature; not a decoder. */
int32_t podcast_cache_has_media_signature(const uint8_t *bytes, int32_t length);
/* A complete, non-empty regular file has positive size; anything else returns 0. */
int64_t podcast_cache_file_size(const char *directory, const char *name);
/* The part and final must be in the same private directory. expected_size=-1 skips length matching.
 * Returns 0 after atomic publication, -1 on failure; caller owns failed partials. */
int32_t podcast_cache_commit(const char *directory, const char *part_name,
                             const char *final_name, int64_t expected_size);
/* Newline-separated final names; empty means clear. Removes unlisted finalized files and stale
 * recognized .part files. Returns number removed or -1. Call only with no active writer. */
int32_t podcast_cache_prune(const char *directory, const char *keep_names);

#ifdef __cplusplus
}
#endif

#endif
