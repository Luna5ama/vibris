#pragma once

#include "source_preparer.hpp"

namespace vibris::mcp {

class SourceDelivery {
public:
    virtual ~SourceDelivery() = default;
    [[nodiscard]] virtual PreparedSource deliver(const std::filesystem::path& archive,
        const control::v2::PreparedSourceRef& provenance) const = 0;
};

class LocalSourceDelivery final : public SourceDelivery {
public:
    explicit LocalSourceDelivery(std::filesystem::path pending_root);
    [[nodiscard]] PreparedSource deliver(const std::filesystem::path& archive,
        const control::v2::PreparedSourceRef& provenance) const override;
private:
    std::filesystem::path pending_root_;
};

} // namespace vibris::mcp
