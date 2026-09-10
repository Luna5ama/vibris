#include "source_delivery.hpp"

#include "config_document.hpp"
#include "state_error.hpp"

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <Windows.h>

#include <system_error>
#include <vector>

namespace vibris::mcp {

LocalSourceDelivery::LocalSourceDelivery(std::filesystem::path pending_root)
    : pending_root_(std::move(pending_root)) {}

PreparedSource LocalSourceDelivery::deliver(const std::filesystem::path& archive,
    const control::v2::PreparedSourceRef& provenance) const {
    const auto uuid = detail::generate_uuid();
    const auto directory = pending_root_ / uuid;
    const auto output_path = directory / "source.tar.zst";
    std::error_code error;
    if (!std::filesystem::create_directory(directory, error) || error) {
        throw StateError("INTERNAL_ERROR", "Could not reserve the delivered source directory.", true);
    }
    HANDLE input = INVALID_HANDLE_VALUE;
    HANDLE output = INVALID_HANDLE_VALUE;
    try {
        input = CreateFileW(archive.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN | FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
        output = CreateFileW(output_path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW,
            FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN, nullptr);
        if (input == INVALID_HANDLE_VALUE || output == INVALID_HANDLE_VALUE || GetFileType(input) != FILE_TYPE_DISK) {
            throw StateError("JOB_CHECKPOINT_ERROR", "The queued source archive is unavailable.", false);
        }
        BY_HANDLE_FILE_INFORMATION information{};
        if (!GetFileInformationByHandle(input, &information) ||
            (information.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) != 0) {
            throw StateError("JOB_CHECKPOINT_ERROR", "The queued source archive is not an ordinary file.", false);
        }
        LARGE_INTEGER size{};
        if (!GetFileSizeEx(input, &size) || size.QuadPart < 0 ||
            static_cast<std::uint64_t>(size.QuadPart) != provenance.compressed_bytes()) {
            throw StateError("JOB_CHECKPOINT_ERROR", "The queued source archive size changed.", false);
        }
        std::vector<std::byte> buffer(1024 * 1024);
        std::uint64_t copied = 0;
        while (true) {
            DWORD read = 0;
            if (!ReadFile(input, buffer.data(), static_cast<DWORD>(buffer.size()), &read, nullptr)) {
                throw StateError("INTERNAL_ERROR", "Could not read the queued source archive.", true);
            }
            if (read == 0) break;
            DWORD written = 0;
            if (!WriteFile(output, buffer.data(), read, &written, nullptr) || written != read) {
                throw StateError("INTERNAL_ERROR", "Could not deliver the source archive.", true);
            }
            copied += read;
        }
        if (copied != provenance.compressed_bytes() || !FlushFileBuffers(output)) {
            throw StateError("INTERNAL_ERROR", "Could not finish source archive delivery.", true);
        }
        if (!CloseHandle(output)) {
            output = INVALID_HANDLE_VALUE;
            throw StateError("INTERNAL_ERROR", "Could not close the delivered source archive.", true);
        }
        output = INVALID_HANDLE_VALUE;
        if (!CloseHandle(input)) {
            input = INVALID_HANDLE_VALUE;
            throw StateError("INTERNAL_ERROR", "Could not close the queued source archive.", true);
        }
        input = INVALID_HANDLE_VALUE;
        auto reference = provenance;
        reference.set_source_uuid(uuid);
        return PreparedSource(std::move(reference), directory, output_path, 1,
            provenance.requested_revision(), provenance.resolved_revision());
    } catch (...) {
        if (output != INVALID_HANDLE_VALUE) CloseHandle(output);
        if (input != INVALID_HANDLE_VALUE) CloseHandle(input);
        std::error_code ignored;
        std::filesystem::remove_all(directory, ignored);
        throw;
    }
}

} // namespace vibris::mcp
