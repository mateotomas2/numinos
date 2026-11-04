# Readium's reader loads scripts and classes in ways the shrinker can't see,
# so it's kept whole. Everything else can be removed if it's unused.
-keep class org.readium.** { *; }
