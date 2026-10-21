# Roadmap

The roadmap lives in GitHub issues, grouped by milestone:

- **M1 · Daily driver**: real-hardware verification (Cast, DLNA, Jellyfin), large-library
  performance, playback robustness, release pipeline.
- **M2 · Parity**: more providers, crossfade, equaliser, smart playlists, scrobbling, widgets,
  tablets, Wear OS.
- **M3 · Beyond**: unified sources (Downloaded / Library / Everything, where Everything is a
  continuo server's catalogue and requests), bandwidth-aware quality, bit-perfect output, provider
  plugin docs. Polyphony is a player first: every acquisition feature only appears with a
  continuo-linked server, and a plain Subsonic or Jellyfin server sees none of it. Third-party
  streaming catalogs (Spotify, Music Assistant) stay out of scope.

`issues.json` is the seed used to create them (`python3 scripts/seed_issues.py`). Edit issues on
GitHub after seeding; the JSON is a starting point, not the source of truth.
