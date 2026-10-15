#pragma once

#include <string>
#include <string_view>
#include <vector>

#include "unigis/campus.hpp"

namespace unigis {

/** Lowercases ASCII and Cyrillic, folds ё -> е. Invalid UTF-8 bytes are dropped. */
std::u32string foldForSearch(std::string_view utf8);

struct SearchHit {
    int building = -1;
    int floor = -1;
    int room = -1;
    int score = 0;  // lower is better
};

/**
 * Finds rooms by number or name. Corridors are never returned. An empty query lists every room.
 * Pass [building]/[floor] >= 0 to restrict the scope. Results are ordered by match quality,
 * then by their order in the data.
 */
std::vector<SearchHit> searchRooms(const Campus& campus, std::string_view query, int building = -1, int floor = -1,
                                   size_t limit = 200);

}  // namespace unigis
