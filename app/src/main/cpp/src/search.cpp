#include "unigis/search.hpp"

#include <algorithm>

namespace unigis {

namespace {

char32_t foldCodePoint(char32_t c) {
    if (c >= U'A' && c <= U'Z') return c + 32;
    if (c >= 0x0410 && c <= 0x042F) return c + 32;  // А-Я -> а-я
    if (c == 0x0401 || c == 0x0451) return 0x0435;   // Ё, ё -> е
    return c;
}

/** Match quality of [needle] in [hay]; -1 if absent. */
int matchRank(const std::u32string& hay, const std::u32string& needle, bool isId) {
    if (needle.empty()) return 6;
    if (hay == needle) return isId ? 0 : 1;
    if (hay.compare(0, needle.size(), needle) == 0) return isId ? 2 : 3;
    const size_t at = hay.find(needle);
    if (at == std::u32string::npos) return -1;
    // Start of a word ("ауд. 426", "кабинет 101") ranks above a match mid-word.
    const char32_t before = hay[at - 1];
    return (before == U' ' || before == U'-' || before == U'.' || before == U'(') ? 4 : 5;
}

}  // namespace

std::u32string foldForSearch(std::string_view s) {
    std::u32string out;
    out.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        const unsigned char b = static_cast<unsigned char>(s[i]);
        char32_t cp;
        size_t len;
        if (b < 0x80) { cp = b; len = 1; }
        else if ((b & 0xE0) == 0xC0) { cp = b & 0x1F; len = 2; }
        else if ((b & 0xF0) == 0xE0) { cp = b & 0x0F; len = 3; }
        else if ((b & 0xF8) == 0xF0) { cp = b & 0x07; len = 4; }
        else { ++i; continue; }
        if (i + len > s.size()) break;
        bool ok = true;
        for (size_t k = 1; k < len; ++k) {
            const unsigned char cb = static_cast<unsigned char>(s[i + k]);
            if ((cb & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (cb & 0x3F);
        }
        if (!ok) { ++i; continue; }
        out.push_back(foldCodePoint(cp));
        i += len;
    }
    // Trim surrounding whitespace so "  101 " still matches.
    const auto notSpace = [](char32_t c) { return c != U' ' && c != U'\t'; };
    out.erase(out.begin(), std::find_if(out.begin(), out.end(), notSpace));
    out.erase(std::find_if(out.rbegin(), out.rend(), notSpace).base(), out.end());
    return out;
}

std::vector<SearchHit> searchRooms(const Campus& campus, std::string_view query, int building, int floor,
                                   size_t limit) {
    const std::u32string needle = foldForSearch(query);
    std::vector<SearchHit> hits;
    for (int b = 0; b < static_cast<int>(campus.buildings.size()); ++b) {
        if (building >= 0 && b != building) continue;
        const auto& floors = campus.buildings[b].floors;
        for (int f = 0; f < static_cast<int>(floors.size()); ++f) {
            if (floor >= 0 && f != floor) continue;
            const auto& rooms = floors[f].rooms;
            for (int r = 0; r < static_cast<int>(rooms.size()); ++r) {
                if (rooms[r].category == RoomCategory::Corridor) continue;
                const int byId = matchRank(foldForSearch(rooms[r].id), needle, true);
                const int byName = matchRank(foldForSearch(rooms[r].name), needle, false);
                int score = -1;
                if (byId >= 0) score = byId;
                if (byName >= 0 && (score < 0 || byName < score)) score = byName;
                if (score >= 0) hits.push_back({b, f, r, score});
            }
        }
    }
    std::stable_sort(hits.begin(), hits.end(), [](const SearchHit& a, const SearchHit& b) { return a.score < b.score; });
    if (hits.size() > limit) hits.resize(limit);
    return hits;
}

}  // namespace unigis
