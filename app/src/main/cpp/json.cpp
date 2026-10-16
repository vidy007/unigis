#include "unigis.hpp"

#include <cstdlib>

namespace unigis::json {

namespace {

const Value kNull;
const Value::Array kEmptyArray;

class Parser {
public:
    explicit Parser(std::string_view text) : s_(text) {}

    Value parseDocument() {
        skipWs();
        Value v = parseValue(0);
        skipWs();
        if (pos_ != s_.size()) fail("trailing characters");
        return v;
    }

private:
    static constexpr int kMaxDepth = 64;

    std::string_view s_;
    size_t pos_ = 0;

    [[noreturn]] void fail(const char* what) const {
        throw ParseError(std::string("JSON: ") + what + " at offset " + std::to_string(pos_));
    }

    bool atEnd() const { return pos_ >= s_.size(); }
    char peek() const { return atEnd() ? '\0' : s_[pos_]; }

    void skipWs() {
        while (!atEnd() && (s_[pos_] == ' ' || s_[pos_] == '\t' || s_[pos_] == '\n' || s_[pos_] == '\r')) ++pos_;
    }

    void expect(char c) {
        if (peek() != c) fail("unexpected character");
        ++pos_;
    }

    bool consumeLiteral(std::string_view lit) {
        if (s_.substr(pos_, lit.size()) != lit) return false;
        pos_ += lit.size();
        return true;
    }

    Value parseValue(int depth) {
        if (depth > kMaxDepth) fail("nesting too deep");
        switch (peek()) {
            case '{': return parseObject(depth);
            case '[': return parseArray(depth);
            case '"': return Value(parseString());
            case 't': if (consumeLiteral("true")) return Value(true); break;
            case 'f': if (consumeLiteral("false")) return Value(false); break;
            case 'n': if (consumeLiteral("null")) return Value(); break;
            default:
                if (peek() == '-' || (peek() >= '0' && peek() <= '9')) return parseNumber();
        }
        fail("unexpected token");
    }

    Value parseObject(int depth) {
        expect('{');
        Value::Object obj;
        skipWs();
        if (peek() == '}') { ++pos_; return Value(std::move(obj)); }
        while (true) {
            skipWs();
            if (peek() != '"') fail("expected key");
            std::string key = parseString();
            skipWs();
            expect(':');
            skipWs();
            obj[std::move(key)] = parseValue(depth + 1);
            skipWs();
            if (peek() == ',') { ++pos_; continue; }
            expect('}');
            return Value(std::move(obj));
        }
    }

    Value parseArray(int depth) {
        expect('[');
        Value::Array arr;
        skipWs();
        if (peek() == ']') { ++pos_; return Value(std::move(arr)); }
        while (true) {
            skipWs();
            arr.push_back(parseValue(depth + 1));
            skipWs();
            if (peek() == ',') { ++pos_; continue; }
            expect(']');
            return Value(std::move(arr));
        }
    }

    Value parseNumber() {
        const size_t start = pos_;
        if (peek() == '-') ++pos_;
        while (!atEnd() && std::string_view("0123456789.eE+-").find(s_[pos_]) != std::string_view::npos) ++pos_;
        const std::string token(s_.substr(start, pos_ - start));
        char* end = nullptr;
        const double d = std::strtod(token.c_str(), &end);
        if (end != token.c_str() + token.size()) fail("bad number");
        return Value(d);
    }

    unsigned parseHex4() {
        if (pos_ + 4 > s_.size()) fail("bad \\u escape");
        unsigned v = 0;
        for (int i = 0; i < 4; ++i) {
            const char c = s_[pos_++];
            v <<= 4;
            if (c >= '0' && c <= '9') v |= c - '0';
            else if (c >= 'a' && c <= 'f') v |= c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') v |= c - 'A' + 10;
            else fail("bad \\u escape");
        }
        return v;
    }

    static void appendUtf8(std::string& out, unsigned cp) {
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }

    std::string parseString() {
        expect('"');
        std::string out;
        while (true) {
            if (atEnd()) fail("unterminated string");
            const char c = s_[pos_++];
            if (c == '"') return out;
            if (static_cast<unsigned char>(c) < 0x20) fail("control character in string");
            if (c != '\\') { out += c; continue; }
            if (atEnd()) fail("unterminated escape");
            switch (s_[pos_++]) {
                case '"': out += '"'; break;
                case '\\': out += '\\'; break;
                case '/': out += '/'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'n': out += '\n'; break;
                case 'r': out += '\r'; break;
                case 't': out += '\t'; break;
                case 'u': {
                    unsigned cp = parseHex4();
                    if (cp >= 0xD800 && cp <= 0xDBFF) {
                        if (!consumeLiteral("\\u")) fail("lone surrogate");
                        const unsigned lo = parseHex4();
                        if (lo < 0xDC00 || lo > 0xDFFF) fail("bad surrogate pair");
                        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    }
                    appendUtf8(out, cp);
                    break;
                }
                default: fail("bad escape");
            }
        }
    }
};

}  // namespace

const Value::Array& Value::items() const { return isArray() ? array_ : kEmptyArray; }

const Value& Value::operator[](size_t i) const {
    return isArray() && i < array_.size() ? array_[i] : kNull;
}

const Value& Value::operator[](std::string_view key) const {
    if (!isObject()) return kNull;
    auto it = object_.find(key);
    return it == object_.end() ? kNull : it->second;
}

Value parse(std::string_view text) { return Parser(text).parseDocument(); }

}  // namespace unigis::json
