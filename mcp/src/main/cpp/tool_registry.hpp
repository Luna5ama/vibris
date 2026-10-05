#pragma once

#include <functional>
#include <string>
#include <string_view>
#include <variant>

#include <nlohmann/json.hpp>

namespace vibris::mcp {

using Json = nlohmann::json;

struct ToolFailure final {
    std::string code;
    std::string message;
    bool retryable = false;
    Json details = Json::object();
};

using ToolOutcome = std::variant<Json, ToolFailure>;
using ToolDispatch = std::function<ToolOutcome(std::string_view, const Json&)>;

enum class InvocationErrorCode {
    UnknownTool,
    InvalidArguments,
};

struct InvocationError final {
    InvocationErrorCode code;
    std::string message;
    Json data = nullptr;
};

using InvocationResult = std::variant<Json, InvocationError>;

class ToolRegistry final {
public:
    explicit ToolRegistry(ToolDispatch dispatch = {});

    [[nodiscard]] const Json& definitions() const noexcept;
    [[nodiscard]] InvocationResult invoke(std::string_view name, const Json& arguments) const;

private:
    Json strict_definitions_;
    Json definitions_;
    ToolDispatch dispatch_;
};

} // namespace vibris::mcp
