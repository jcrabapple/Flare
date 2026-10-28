package dev.dimension.flare.push

import dev.dimension.flare.model.MicroBlogKey

/**
 * Parses a UnifiedPush `instance` string back to its account key (DESIGN.md D8: the instance is
 * `accountKey.toString()`, which `MicroBlogKey.valueOf` round-trips). Returns null for anything
 * that cannot be an account key — [MicroBlogKey.valueOf] is lenient, so also reject the shapes it
 * recovers without a host (e.g. plain garbage, or a string cut short at a comma) — so the caller
 * can unregister the unknown instance.
 */
internal fun parseInstance(instance: String): MicroBlogKey? =
    runCatching { MicroBlogKey.valueOf(instance) }
        .getOrNull()
        ?.takeIf { it.id.isNotBlank() && it.host.isNotBlank() }
