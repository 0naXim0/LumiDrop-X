# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Keep ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Keep app server classes
-keep class com.lumidrop.x.server.** { *; }

# Keep app transfer-tracking classes (Phase 10). ProgressTrackingInputStream
# overrides InputStream.read()/close(), called polymorphically from inside
# NanoHTTPD's own response-writing code — an explicit keep removes any doubt
# about R8 correctly retaining those overrides across shrink/optimize passes.
-keep class com.lumidrop.x.transfer.** { *; }
