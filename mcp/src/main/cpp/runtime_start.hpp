#pragma once

#include <atomic>
#include <filesystem>
#include <string>

#include "tool_registry.hpp"

namespace vibris::mcp {

class RuntimeStart final {
public:
    RuntimeStart(std::string address, std::filesystem::path deployment);
    [[nodiscard]] ToolOutcome ensure_online(const Json& arguments) const;
    void interrupt() noexcept { interrupted_.store(true); }

private:
    std::string address_;
    std::filesystem::path deployment_;
    std::atomic_bool interrupted_ = false;
};

} // namespace vibris::mcp
