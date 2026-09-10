#pragma once

#include "vibris_control.pb.h"

#include <cstdint>

namespace vibris::mcp {

inline constexpr std::uint32_t protocol_major = 3;
inline constexpr std::uint32_t protocol_minor = 0;
inline constexpr const char* unsupported_version_code = "UNSUPPORTED_VERSION";

[[nodiscard]] inline bool protocol_version_supported(
    const bool present,
    const ::vibris::control::v2::ProtocolVersion& version) noexcept {
    return present && version.major() == protocol_major;
}

}
