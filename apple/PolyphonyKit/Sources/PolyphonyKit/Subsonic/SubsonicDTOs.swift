import Foundation

// The JSON Subsonic servers send (`f=json`), as far as Polyphony reads it. Every field but the id is
// optional: servers differ in what they fill in, and a missing field must never fail a whole page.

struct SubsonicErrorDTO: Decodable, Sendable {
    let code: Int?
    let message: String?
}

struct ArtistDTO: Decodable, Sendable {
    let id: String
    let name: String?
    let coverArt: String?
    let albumCount: Int?
    let starred: String?
    let album: [AlbumDTO]?
}

struct AlbumDTO: Decodable, Sendable {
    let id: String
    let name: String?
    let artist: String?
    let artistId: String?
    let coverArt: String?
    let songCount: Int?
    let duration: Double?
    let year: Int?
    let genre: String?
    let starred: String?
    let userRating: Int?
    let song: [ChildDTO]?
}

struct ChildDTO: Decodable, Sendable {
    let id: String
    let title: String?
    let album: String?
    let albumId: String?
    let artist: String?
    let artistId: String?
    let track: Int?
    let discNumber: Int?
    let duration: Double?
    let bitRate: Int?
    let bitDepth: Int?
    let samplingRate: Int?
    let suffix: String?
    let size: Int64?
    let coverArt: String?
    let starred: String?
    let userRating: Int?
    let replayGain: ReplayGainDTO?
    let year: Int?
    let genre: String?
    let playCount: Int?
    let path: String?
    let channelCount: Int?
}

struct ReplayGainDTO: Decodable, Sendable {
    let trackGain: Double?
    let albumGain: Double?
    let trackPeak: Double?
    let albumPeak: Double?
}

struct ArtistIndexDTO: Decodable, Sendable {
    let artist: [ArtistDTO]?
}

struct ArtistsDTO: Decodable, Sendable {
    let index: [ArtistIndexDTO]?
}

struct AlbumListDTO: Decodable, Sendable {
    let album: [AlbumDTO]?
}

struct PlaylistDTO: Decodable, Sendable {
    let id: String
    let name: String?
    let comment: String?
    let owner: String?
    let `public`: Bool?
    let songCount: Int?
    let duration: Double?
    let coverArt: String?
    let entry: [ChildDTO]?

    var model: Playlist {
        Playlist(id: id, name: name ?? "", comment: comment, owner: owner, isPublic: `public` ?? false,
                 songCount: songCount ?? 0, duration: duration ?? 0, coverArtID: coverArt, tracks: (entry ?? []).map(\.model))
    }
}

struct PlaylistsDTO: Decodable, Sendable {
    let playlist: [PlaylistDTO]?
}

struct GenreDTO: Decodable, Sendable {
    let value: String?
    let songCount: Int?
    let albumCount: Int?
}

struct GenresDTO: Decodable, Sendable {
    let genre: [GenreDTO]?
}

struct ArtistInfoDTO: Decodable, Sendable {
    let biography: String?
    let largeImageUrl: String?
    let similarArtist: [ArtistDTO]?
}

struct SongsDTO: Decodable, Sendable {
    let song: [ChildDTO]?
}

struct StructuredLyricsDTO: Decodable, Sendable {
    struct Line: Decodable, Sendable { let start: Int?; let value: String? }
    let synced: Bool?
    let line: [Line]?
}

struct LyricsListDTO: Decodable, Sendable {
    let structuredLyrics: [StructuredLyricsDTO]?
}

struct SearchResult3DTO: Decodable, Sendable {
    let artist: [ArtistDTO]?
    let album: [AlbumDTO]?
    let song: [ChildDTO]?
}

extension ArtistDTO {
    var model: Artist {
        Artist(id: id, name: name ?? "", albumCount: albumCount ?? 0, coverArtID: coverArt, starred: starred != nil)
    }
}

extension AlbumDTO {
    var model: Album {
        Album(id: id, name: name ?? "", artistName: artist, artistID: artistId, year: year, genre: genre,
              songCount: songCount ?? 0, duration: duration ?? 0, coverArtID: coverArt, starred: starred != nil,
              rating: userRating.flatMap { $0 > 0 ? $0 : nil }, tracks: (song ?? []).map(\.model))
    }
}

extension ChildDTO {
    var model: Track {
        Track(id: id, title: title ?? "", albumID: albumId, albumName: album, artistName: artist, artistID: artistId,
              trackNumber: track, discNumber: discNumber, duration: duration ?? 0, bitRateKbps: bitRate,
              bitDepth: bitDepth, sampleRateHz: samplingRate, container: suffix, sizeBytes: size,
              coverArtID: coverArt, starred: starred != nil, rating: userRating.flatMap { $0 > 0 ? $0 : nil },
              replayGain: replayGain.flatMap { rg in
                  rg.trackGain == nil && rg.albumGain == nil ? nil
                      : ReplayGain.Tags(trackGainDb: rg.trackGain, albumGainDb: rg.albumGain, trackPeak: rg.trackPeak, albumPeak: rg.albumPeak)
              },
              details: Track.Details(year: year, genre: genre, playCount: playCount, path: path, channels: channelCount))
    }
}
