import PolyphonyKit
import SwiftUI

/// What a list is narrowed to: the toolbar's filter button.
struct FilterMenu: View {
    @Environment(AppModel.self) private var model
    @Binding var stored: String
    var genres: [String] = []
    var offersDownloaded = true
    var offersYear = true
    var offersRating = true

    private var filter: LibraryFilter { LibraryFilter(stored: stored) }

    private func change(_ edit: (inout LibraryFilter) -> Void) {
        var f = filter
        edit(&f)
        stored = f.stored
    }

    private func decade(_ from: Int) -> String { "\(from)s" }

    var body: some View {
        Menu {
            if model.usesRatings && offersRating {
                Picker("Rating", selection: Binding(get: { filter.minRating }, set: { value in change { $0.minRating = value } })) {
                    Text("Any").tag(0)
                    ForEach(1...5, id: \.self) { Text("\($0)★ and up").tag($0) }
                }
            } else {
                Toggle("Favourites Only", isOn: Binding(get: { filter.favouritesOnly }, set: { value in change { $0.favouritesOnly = value } }))
            }
            if offersDownloaded {
                Toggle("Downloaded Only", isOn: Binding(get: { filter.downloadedOnly }, set: { value in change { $0.downloadedOnly = value } }))
            }
            if !genres.isEmpty {
                Picker("Genre", selection: Binding(get: { filter.genre }, set: { value in change { $0.genre = value } })) {
                    Text("Any").tag(String?.none)
                    ForEach(genres, id: \.self) { Text($0).tag(String?.some($0)) }
                }
            }
            if offersYear {
                Picker("Decade", selection: Binding(get: { filter.yearFrom }, set: { value in
                    change {
                        $0.yearFrom = value
                        $0.yearTo = value.map { $0 + 9 }
                    }
                })) {
                    Text("Any").tag(Int?.none)
                    ForEach(Array(stride(from: 2020, through: 1950, by: -10)), id: \.self) { Text(decade($0)).tag(Int?.some($0)) }
                }
            }
            if filter.isActive {
                Divider()
                Button("Clear Filters") { stored = LibraryFilter().stored }
            }
        } label: {
            Label("Filter", systemImage: filter.isActive ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
        }
        .help("Filter this list")
    }
}
