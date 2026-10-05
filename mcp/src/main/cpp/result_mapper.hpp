#pragma once

#include "vibris_control.pb.h"

#include <nlohmann/json_fwd.hpp>
#include <string_view>

namespace google::protobuf {
class Message;
}

namespace vibris::mcp {

class ResultMapper final {
public:
    [[nodiscard]] static nlohmann::json message(const google::protobuf::Message& value);
    [[nodiscard]] static nlohmann::json list_presets(
        const ::vibris::control::v2::ListPresetsResponse& response);
    [[nodiscard]] static nlohmann::json list_resources(
        const ::vibris::control::v2::ListResourcesResponse& response);
    [[nodiscard]] static nlohmann::json validation(
        const ::vibris::control::v2::ValidateContextResponse& response);
    [[nodiscard]] static nlohmann::json status(
        const ::vibris::control::v2::GetStatusResponse& response);
    [[nodiscard]] static nlohmann::json artifacts(
        const ::vibris::control::v2::ManageArtifactsResponse& response);
    [[nodiscard]] static nlohmann::json server_message(
        const ::vibris::control::v2::ServerMessage& response);
    static void finalize_provenance(nlohmann::json& result);
    [[nodiscard]] static nlohmann::json agent_projection(
        std::string_view tool, nlohmann::json result, const nlohmann::json& arguments);
};

}
