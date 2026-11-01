#pragma once

#include <memory>
#include <string>
#include <string_view>

#include "grpc_client.hpp"
#include "tool_registry.hpp"

namespace vibris::mcp {

class McpBackend final {
public:
    explicit McpBackend(std::string server_address);
    ~McpBackend();

    McpBackend(const McpBackend&) = delete;
    McpBackend& operator=(const McpBackend&) = delete;

    [[nodiscard]] ToolOutcome dispatch(std::string_view name, const Json& arguments);
    void interrupt_waits();
    [[nodiscard]] std::optional<GrpcClientStats> shutdown();

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace vibris::mcp
