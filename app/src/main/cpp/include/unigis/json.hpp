#pragma once

#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace unigis::json {

struct ParseError : std::runtime_error {
    using std::runtime_error::runtime_error;
};

/** Minimal JSON DOM: enough for the campus data files, no external dependencies. */
class Value {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };

    Value() = default;
    explicit Value(bool b) : type_(Type::Bool), bool_(b) {}
    explicit Value(double n) : type_(Type::Number), number_(n) {}
    explicit Value(std::string s) : type_(Type::String), string_(std::move(s)) {}

    static Value array() { Value v; v.type_ = Type::Array; return v; }
    static Value object() { Value v; v.type_ = Type::Object; return v; }

    Type type() const { return type_; }
    bool isNull() const { return type_ == Type::Null; }
    bool isNumber() const { return type_ == Type::Number; }
    bool isString() const { return type_ == Type::String; }
    bool isArray() const { return type_ == Type::Array; }
    bool isObject() const { return type_ == Type::Object; }

    double asNumber(double fallback = 0.0) const { return isNumber() ? number_ : fallback; }
    float asFloat(float fallback = 0.f) const { return isNumber() ? static_cast<float>(number_) : fallback; }
    int asInt(int fallback = 0) const { return isNumber() ? static_cast<int>(number_) : fallback; }
    bool asBool(bool fallback = false) const { return type_ == Type::Bool ? bool_ : fallback; }
    const std::string& asString() const;
    std::string asString(const std::string& fallback) const { return isString() ? string_ : fallback; }

    const std::vector<Value>& items() const;
    std::vector<Value>& items() { return array_; }
    size_t size() const { return isArray() ? array_.size() : isObject() ? object_.size() : 0; }
    const Value& operator[](size_t i) const;

    /** Missing keys (or non-objects) yield a shared null value. */
    const Value& operator[](std::string_view key) const;
    bool has(std::string_view key) const;
    std::map<std::string, Value, std::less<>>& members() { return object_; }
    const std::map<std::string, Value, std::less<>>& members() const { return object_; }

private:
    Type type_ = Type::Null;
    bool bool_ = false;
    double number_ = 0.0;
    std::string string_;
    std::vector<Value> array_;
    std::map<std::string, Value, std::less<>> object_;
};

/** Throws ParseError with a byte offset on malformed input. */
Value parse(std::string_view text);

}  // namespace unigis::json
