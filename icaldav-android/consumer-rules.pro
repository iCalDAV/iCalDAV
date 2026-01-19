# iCalDAV Android - Consumer ProGuard Rules
# These rules are applied to consuming apps

# Keep model classes for reflection-based serialization
-keep class org.onekash.icaldav.model.** { *; }
-keep class org.onekash.icaldav.android.** { *; }
