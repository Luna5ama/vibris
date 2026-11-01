#include "mcp_stdio_server.hpp"
#include "native_metrics.hpp"

#include <string>
#include <algorithm>
#include <chrono>
#include <utility>

#include <nlohmann/json.hpp>

namespace vibris::mcp {
namespace {

constexpr std::string_view protocol_version = "2024-11-05";

Json error_response(Json id, int code, const char* message, Json data = nullptr) {
    Json error{{"code", code}, {"message", message}};
    if (!data.is_null()) error["data"] = std::move(data);
    return Json{{"jsonrpc", "2.0"}, {"id", std::move(id)}, {"error", std::move(error)}};
}

Json result_response(Json id, Json result) {
    return Json{{"jsonrpc", "2.0"}, {"id", std::move(id)}, {"result", std::move(result)}};
}

bool valid_id(const Json& id) {
    return id.is_string() || id.is_number_integer() || id.is_number_unsigned();
}

Json id_from_prefix(std::string_view prefix) {
    constexpr std::size_t max_id_bytes = 256;
    const auto key = prefix.find("\"id\"");
    const auto colon = key == std::string_view::npos ? key : prefix.find(':', key + 4);
    const auto start = colon == std::string_view::npos ? colon : prefix.find_first_not_of(" \t\r\n", colon + 1);
    if (start == std::string_view::npos) return nullptr;

    std::size_t end = start;
    if (prefix[start] == '"') {
        bool escaped = false;
        for (++end; end < prefix.size() && end - start <= max_id_bytes; ++end) {
            if (!escaped && prefix[end] == '"') {
                ++end;
                break;
            }
            escaped = !escaped && prefix[end] == '\\';
            if (prefix[end] != '\\') escaped = false;
        }
    } else {
        while (end < prefix.size() && end - start <= max_id_bytes &&
               (prefix[end] == '-' || (prefix[end] >= '0' && prefix[end] <= '9'))) {
            ++end;
        }
    }
    if (end == start || end - start > max_id_bytes) return nullptr;
    try {
        auto id = Json::parse(prefix.substr(start, end - start));
        return valid_id(id) ? id : Json(nullptr);
    } catch (const Json::parse_error&) {
        return nullptr;
    }
}

bool valid_params(const Json& request) {
    return !request.contains("params") || request["params"].is_object();
}

} // namespace

McpStdioServer::McpStdioServer(std::istream& input, std::ostream& output, const ToolRegistry& tools,
    std::function<void()> on_input_closed) noexcept
    : on_input_closed_(std::move(on_input_closed)), input_(input), output_(output), tools_(tools) {}

int McpStdioServer::run() {
    std::string line;
    line.reserve(4096);
    bool oversized = false;

    for (char value = '\0'; input_.get(value);) {
        if (value != '\n') {
            if (!oversized && line.size() < max_message_bytes) {
                line.push_back(value);
            } else {
                oversized = true;
            }
            continue;
        }

        if (oversized) {
            write(error_response(id_from_prefix(line), -32600, "Request exceeds the input limit.",
                                 {{"code", "REQUEST_TOO_LARGE"}, {"retryable", false}}));
        } else {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            handle_line(line);
        }
        record_native_metrics();
        line.clear();
        oversized = false;
    }

    if (oversized) {
        write(error_response(id_from_prefix(line), -32600, "Request exceeds the input limit.",
                             {{"code", "REQUEST_TOO_LARGE"}, {"retryable", false}}));
    } else if (!line.empty()) {
        if (line.back() == '\r') line.pop_back();
        handle_line(line);
        record_native_metrics();
    }
    if (on_input_closed_) on_input_closed_();
    for (auto& wait : waits_) wait.get();
    waits_.clear();
    return 0;
}

void McpStdioServer::handle_line(std::string_view line) {
    Json request;
    try {
        request = Json::parse(line);
    } catch (const Json::parse_error&) {
        write(error_response(nullptr, -32700, "Parse error"));
        return;
    }

    if (!request.is_object() || !request.contains("jsonrpc") || !request["jsonrpc"].is_string() ||
        request["jsonrpc"].get_ref<const std::string&>() != "2.0" || !request.contains("method") ||
        !request["method"].is_string()) {
        write(error_response(nullptr, -32600, "Invalid Request"));
        return;
    }

    const bool notification = !request.contains("id");
    if (!notification && !valid_id(request["id"])) {
        write(error_response(nullptr, -32600, "Invalid Request"));
        return;
    }

    const auto& method = request["method"].get_ref<const std::string&>();
    if (notification) return;
    const auto& id = request["id"];

    if (method == "initialize") {
        if (!valid_params(request)) {
            write(error_response(id, -32602, "Invalid params"));
            return;
        }
        write(result_response(id,
                              {{"protocolVersion", std::string(protocol_version)},
                               {"capabilities",
                                {{"resources", {{"listChanged", false}, {"subscribe", false}}},
                                 {"tools", {{"listChanged", false}}}}},
                               {"serverInfo", {{"name", "vibris-mcp"}, {"version", "0.1.0"}}}}));
        return;
    }

    if (method == "resources/list") {
        if (!valid_params(request)) {
            write(error_response(id, -32602, "Invalid params"));
            return;
        }
        write(result_response(id, {{"resources", Json::array()}}));
        return;
    }

    if (method == "resources/templates/list") {
        if (!valid_params(request)) {
            write(error_response(id, -32602, "Invalid params"));
            return;
        }
        write(result_response(id, {{"resourceTemplates", Json::array()}}));
        return;
    }

    if (method == "tools/list") {
        if (!valid_params(request)) {
            write(error_response(id, -32602, "Invalid params"));
            return;
        }
        write(result_response(id, {{"tools", tools_.definitions()}}));
        return;
    }

    if (method == "tools/call") {
        if (!request.contains("params") || !request["params"].is_object()) {
            write(error_response(id, -32602, "Invalid params"));
            return;
        }
        const auto& params = request["params"];
        if (!params.contains("name") || !params["name"].is_string() ||
            (params.contains("arguments") && !params["arguments"].is_object())) {
            write(error_response(id, -32602, "Invalid params"));
            return;
        }
        const auto arguments = params.value("arguments", Json::object());
        const auto name = params["name"].get<std::string>();
        if (name == "vibris_job" && arguments.value("operation", Json{}) == "wait") {
            std::erase_if(waits_, [](auto& wait) {
                if (wait.wait_for(std::chrono::seconds(0)) != std::future_status::ready) return false;
                wait.get();
                return true;
            });
            if (waits_.size() >= 8) {
                write(error_response(id, -32000, "Too many concurrent job waits.",
                    {{"code", "WAIT_CAPACITY"}, {"retryable", true}}));
                return;
            }
            waits_.push_back(std::async(std::launch::async, [this, id, name, arguments] {
                invoke(id, name, arguments);
            }));
        } else {
            invoke(id, name, arguments);
        }
        return;
    }

    write(error_response(id, -32601, "Method not found"));
}

void McpStdioServer::invoke(const Json& id, const std::string& name, const Json& arguments) {
    const auto invocation = tools_.invoke(name, arguments);
    if (const auto* error = std::get_if<InvocationError>(&invocation)) {
        write(error_response(id, -32602, error->message.c_str(), error->data));
    } else {
        write(result_response(id, std::get<Json>(invocation)));
    }
}

void McpStdioServer::write(const Json& message) {
    std::scoped_lock lock(output_mutex_);
    output_ << message.dump() << '\n';
    output_.flush();
}

} // namespace vibris::mcp
