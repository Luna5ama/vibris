#include "source_archive.hpp"

#include "source_path_policy.hpp"
#include "state_error.hpp"

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <Windows.h>
#include <bcrypt.h>

#include <archive.h>
#include <archive_entry.h>

#include <algorithm>
#include <array>
#include <limits>
#include <memory>
#include <span>
#include <system_error>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

namespace fs = std::filesystem;

[[noreturn]] void fail(std::string message, bool retryable = true) {
    throw StateError("INTERNAL_ERROR", std::move(message), retryable);
}

class Sha256 final {
public:
    Sha256() {
        if (BCryptOpenAlgorithmProvider(&algorithm_, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0) {
            fail("Could not initialize source hashing.");
        }
        DWORD object_bytes = 0;
        DWORD copied = 0;
        if (BCryptGetProperty(algorithm_, BCRYPT_OBJECT_LENGTH,
                reinterpret_cast<PUCHAR>(&object_bytes), sizeof(object_bytes), &copied, 0) < 0 ||
            copied != sizeof(object_bytes)) {
            fail("Could not size source hashing.");
        }
        object_.resize(object_bytes);
        if (BCryptCreateHash(algorithm_, &hash_, object_.data(), object_bytes, nullptr, 0, 0) < 0) {
            fail("Could not create source hash.");
        }
    }

    Sha256(const Sha256&) = delete;
    Sha256& operator=(const Sha256&) = delete;

    ~Sha256() {
        if (hash_ != nullptr) BCryptDestroyHash(hash_);
        if (algorithm_ != nullptr) BCryptCloseAlgorithmProvider(algorithm_, 0);
    }

    void update(std::span<const std::byte> bytes) {
        if (!bytes.empty() && BCryptHashData(hash_, const_cast<PUCHAR>(
                reinterpret_cast<const UCHAR*>(bytes.data())), static_cast<ULONG>(bytes.size()), 0) < 0) {
            fail("Could not update source hash.");
        }
    }

    void update(std::string_view text) {
        update(std::as_bytes(std::span(text.data(), text.size())));
    }

    std::array<std::byte, 32> finish() {
        std::array<std::byte, 32> result{};
        if (BCryptFinishHash(hash_, reinterpret_cast<PUCHAR>(result.data()),
                static_cast<ULONG>(result.size()), 0) < 0) {
            fail("Could not finish source hash.");
        }
        hash_ = nullptr;
        return result;
    }

private:
    BCRYPT_ALG_HANDLE algorithm_ = nullptr;
    BCRYPT_HASH_HANDLE hash_ = nullptr;
    std::vector<UCHAR> object_;
};

template <typename Integer>
void big_endian(Sha256& digest, Integer value) {
    std::array<std::byte, sizeof(Integer)> bytes{};
    for (std::size_t index = 0; index < bytes.size(); ++index) {
        bytes[bytes.size() - index - 1] = static_cast<std::byte>(value & 0xff);
        value >>= 8;
    }
    digest.update(bytes);
}

std::string hex(std::span<const std::byte> digest) {
    static constexpr char digits[] = "0123456789abcdef";
    std::string result(digest.size() * 2, '0');
    for (std::size_t index = 0; index < digest.size(); ++index) {
        const auto value = std::to_integer<unsigned char>(digest[index]);
        result[index * 2] = digits[value >> 4];
        result[index * 2 + 1] = digits[value & 0xf];
    }
    return result;
}

struct FileDigest final {
    std::string path;
    std::uint64_t size;
    std::array<std::byte, 32> digest;
};

class InputFile final {
public:
    explicit InputFile(const fs::path& path)
        : handle_(CreateFileW(path.c_str(), GENERIC_READ,
              FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
              FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_SEQUENTIAL_SCAN, nullptr)) {
        if (handle_ == INVALID_HANDLE_VALUE) changed();
        FILE_ATTRIBUTE_TAG_INFO information{};
        if (!GetFileInformationByHandleEx(handle_, FileAttributeTagInfo, &information, sizeof(information)) ||
            GetFileType(handle_) != FILE_TYPE_DISK ||
            (information.FileAttributes &
                (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_DEVICE)) != 0) {
            CloseHandle(handle_);
            handle_ = INVALID_HANDLE_VALUE;
            throw StateError("SOURCE_CONTAINS_REPARSE_POINT",
                "The workspace source contains a non-ordinary entry.");
        }
    }

    InputFile(const InputFile&) = delete;
    InputFile& operator=(const InputFile&) = delete;

    ~InputFile() {
        if (handle_ != INVALID_HANDLE_VALUE) CloseHandle(handle_);
    }

    DWORD read(std::span<std::byte> buffer) const {
        DWORD count = 0;
        if (!ReadFile(handle_, buffer.data(), static_cast<DWORD>(buffer.size()), &count, nullptr)) changed();
        return count;
    }

    void close() {
        if (!CloseHandle(handle_)) {
            handle_ = INVALID_HANDLE_VALUE;
            fail("Could not close a workspace source input.");
        }
        handle_ = INVALID_HANDLE_VALUE;
    }

private:
    [[noreturn]] static void changed() {
        throw StateError("SOURCE_CHANGED_DURING_SNAPSHOT",
            "The shader source changed while it was read.", true);
    }

    HANDLE handle_ = INVALID_HANDLE_VALUE;
};

std::string aggregate_hash(std::vector<FileDigest> files) {
    std::ranges::sort(files, [](const FileDigest& left, const FileDigest& right) {
        return std::lexicographical_compare(left.path.begin(), left.path.end(), right.path.begin(), right.path.end(),
            [](const char a, const char b) {
                return static_cast<unsigned char>(a) < static_cast<unsigned char>(b);
            });
    });
    Sha256 digest;
    digest.update("vibris-source-tree-v2");
    const std::array<std::byte, 1> zero{};
    digest.update(zero);
    for (const auto& file : files) {
        const std::array marker{std::byte{'F'}};
        digest.update(marker);
        big_endian(digest, static_cast<std::uint32_t>(file.path.size()));
        digest.update(file.path);
        big_endian(digest, file.size);
        digest.update(file.digest);
    }
    const auto result = digest.finish();
    return hex(result);
}

struct OutputFile final {
    OutputFile(const fs::path& path, const std::uint64_t maximum_bytes)
        : handle(CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW,
              FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN, nullptr)), maximum_bytes(maximum_bytes) {
        if (handle == INVALID_HANDLE_VALUE) fail("Could not exclusively create the source archive.");
    }

    ~OutputFile() {
        if (handle != INVALID_HANDLE_VALUE) CloseHandle(handle);
    }

    HANDLE handle = INVALID_HANDLE_VALUE;
    bool failed = false;
    bool too_large = false;
    std::uint64_t maximum_bytes;
    std::uint64_t written_bytes = 0;
};

la_ssize_t write_output(archive*, void* context, const void* bytes, size_t size) noexcept {
    auto& output = *static_cast<OutputFile*>(context);
    if (size > output.maximum_bytes - output.written_bytes) {
        output.failed = true;
        output.too_large = true;
        return -1;
    }
    const auto* next = static_cast<const std::byte*>(bytes);
    std::size_t remaining = size;
    while (remaining != 0) {
        DWORD written = 0;
        const auto chunk = static_cast<DWORD>((std::min)(remaining, static_cast<std::size_t>(1U << 30)));
        if (!WriteFile(output.handle, next, chunk, &written, nullptr) || written != chunk) {
            output.failed = true;
            return -1;
        }
        next += written;
        remaining -= written;
        output.written_bytes += written;
    }
    return static_cast<la_ssize_t>(size);
}

int close_output(archive*, void* context) noexcept {
    auto& output = *static_cast<OutputFile*>(context);
    if (!FlushFileBuffers(output.handle)) output.failed = true;
    if (!CloseHandle(output.handle)) output.failed = true;
    output.handle = INVALID_HANDLE_VALUE;
    return output.failed ? ARCHIVE_FATAL : ARCHIVE_OK;
}

using WriteArchive = std::unique_ptr<archive, decltype(&archive_write_free)>;

WriteArchive open_writer(OutputFile& output) {
    WriteArchive writer(archive_write_new(), &archive_write_free);
    if (writer == nullptr || archive_write_set_format_pax_restricted(writer.get()) != ARCHIVE_OK ||
        archive_write_add_filter_zstd(writer.get()) != ARCHIVE_OK ||
        archive_write_set_filter_option(writer.get(), "zstd", "compression-level", "3") != ARCHIVE_OK ||
        archive_write_set_filter_option(writer.get(), "zstd", "threads", "1") != ARCHIVE_OK ||
        archive_write_open(writer.get(), &output, nullptr, write_output, close_output) != ARCHIVE_OK) {
        fail("Could not initialize tar.zst source output.");
    }
    return writer;
}

void close_writer(WriteArchive& writer, OutputFile& output) {
    const auto close_result = archive_write_close(writer.get());
    if (output.too_large) {
        throw StateError("SOURCE_TOO_LARGE", "The compressed source archive exceeds server limits.");
    }
    if (close_result != ARCHIVE_OK || output.failed) {
        fail("Could not finish tar.zst source output.");
    }
}

void write_header(archive* writer, std::string_view path, std::uint64_t size, bool directory) {
    std::unique_ptr<archive_entry, decltype(&archive_entry_free)> entry(archive_entry_new(), &archive_entry_free);
    if (entry == nullptr) fail("Could not allocate a tar source entry.");
    archive_entry_set_pathname_utf8(entry.get(), std::string(path).c_str());
    archive_entry_set_perm(entry.get(), directory ? 0755 : 0644);
    archive_entry_set_filetype(entry.get(), directory ? AE_IFDIR : AE_IFREG);
    archive_entry_set_size(entry.get(), directory ? 0 : static_cast<la_int64_t>(size));
    if (archive_write_header(writer, entry.get()) != ARCHIVE_OK) fail("Could not write a tar source header.");
}

std::array<std::byte, 32> hash_file(
    const fs::path& path, const std::uint64_t size, archive* writer = nullptr) {
    InputFile input(path);
    Sha256 digest;
    std::vector<std::byte> buffer(1024 * 1024);
    std::uint64_t total = 0;
    while (true) {
        const auto count = input.read(buffer);
        if (count == 0) break;
        const auto bytes = std::span(buffer.data(), count);
        digest.update(bytes);
        if (writer != nullptr && archive_write_data(writer, bytes.data(), bytes.size()) != count) {
            fail("Could not write tar source data.");
        }
        total += count;
    }
    input.close();
    if (total != size) throw StateError("SOURCE_CHANGED_DURING_SNAPSHOT",
        "The shader source changed while it was archived.", true);
    return digest.finish();
}

std::uint64_t archive_size(const fs::path& path) {
    std::error_code error;
    const auto size = fs::file_size(path, error);
    if (error) fail("Could not inspect the completed source archive.");
    return size;
}

struct GitReader final {
    GitArchivePipe pipe;
    std::vector<std::byte> buffer = std::vector<std::byte>(1024 * 1024);
    std::uint64_t total = 0;
    std::size_t largest = 0;
    std::string error;
};

la_ssize_t read_git(archive* reader, void* context, const void** output) noexcept {
    auto& input = *static_cast<GitReader*>(context);
    try {
        const auto count = input.pipe.read(input.buffer);
        input.total += count;
        input.largest = (std::max)(input.largest, count);
        *output = input.buffer.data();
        return static_cast<la_ssize_t>(count);
    } catch (const std::exception& failure) {
        input.error = failure.what();
        archive_set_error(reader, EIO, "%s", input.error.c_str());
        return ARCHIVE_FATAL;
    }
}

} // namespace

std::uint64_t maximum_source_archive_bytes(const SourceLimits limits) {
    constexpr auto extra = 1024ULL * 1024ULL;
    const auto entries = static_cast<std::uint64_t>(limits.max_files);
    if (entries > (std::numeric_limits<std::uint64_t>::max() - extra) / 4096ULL) {
        return std::numeric_limits<std::uint64_t>::max();
    }
    const auto overhead = entries * 4096ULL + extra;
    if (limits.max_total_bytes > std::numeric_limits<std::uint64_t>::max() - overhead) {
        return std::numeric_limits<std::uint64_t>::max();
    }
    return limits.max_total_bytes + overhead;
}

std::string hash_workspace_source(const fs::path& source, const SourceLimits limits) {
    const auto metadata = enumerate_workspace_tree(source, limits);
    std::vector<FileDigest> files;
    for (const auto& entry : metadata.entries) {
        if (entry.type != WorkspaceEntryType::regular_file) continue;
        files.push_back({entry.relative_path.generic_string(), entry.size,
            hash_file(source / entry.relative_path, entry.size)});
    }
    if (metadata != enumerate_workspace_tree(source, limits)) {
        throw StateError("SOURCE_CHANGED_DURING_SNAPSHOT",
            "The shader source changed while it was verified.", true);
    }
    return aggregate_hash(std::move(files));
}

SourceArchiveResult write_workspace_source_archive(
    const fs::path& source, const fs::path& archive_path, const SourceLimits limits) {
    const auto metadata = enumerate_workspace_tree(source, limits);
    OutputFile output(archive_path, maximum_source_archive_bytes(limits));
    auto writer = open_writer(output);
    std::vector<FileDigest> files;
    for (const auto& entry : metadata.entries) {
        const auto relative = entry.relative_path.generic_string();
        const bool directory = entry.type == WorkspaceEntryType::directory;
        write_header(writer.get(), relative, entry.size, directory);
        if (!directory) {
            files.push_back({relative, entry.size,
                hash_file(source / entry.relative_path, entry.size, writer.get())});
        }
    }
    close_writer(writer, output);
    return {metadata, aggregate_hash(std::move(files)), archive_size(archive_path)};
}

SourceArchiveResult write_commit_source_archive(
    GitArchivePipe source, const fs::path& archive_path, const SourceLimits limits) {
    GitReader input{std::move(source)};
    using ReadArchive = std::unique_ptr<archive, decltype(&archive_read_free)>;
    ReadArchive reader(archive_read_new(), &archive_read_free);
    if (reader == nullptr || archive_read_support_format_tar(reader.get()) != ARCHIVE_OK ||
        archive_read_open(reader.get(), &input, nullptr, read_git, nullptr) != ARCHIVE_OK) {
        fail("Could not open the Git source archive.");
    }
    OutputFile output(archive_path, maximum_source_archive_bytes(limits));
    auto writer = open_writer(output);
    WorkspaceMetadata metadata{{}, 0, 0};
    std::vector<FileDigest> files;
    std::vector<std::byte> buffer(1024 * 1024);
    archive_entry* entry = nullptr;
    int header_result = ARCHIVE_OK;
    while ((header_result = archive_read_next_header(reader.get(), &entry)) == ARCHIVE_OK) {
        const char* pathname = archive_entry_pathname_utf8(entry);
        if (pathname == nullptr) fail("Git emitted a source entry without a UTF-8 path.", false);
        const std::string archived_path(pathname);
        if (archived_path == "shaders/" || archived_path == "shaders") continue;
        const auto type = archive_entry_filetype(entry);
        const bool directory = type == AE_IFDIR;
        if (!directory && type != AE_IFREG) fail("Git emitted a non-ordinary source entry.", false);
        const auto relative_path = SourcePathPolicy{}.archive_relative_path({archived_path,
            directory ? SourceEntryKind::directory : SourceEntryKind::regular_file, false});
        const auto relative = relative_path.generic_string();
        const auto signed_size = archive_entry_size(entry);
        if (signed_size < 0) fail("Git emitted a source entry without a size.", false);
        const auto size = static_cast<std::uint64_t>(signed_size);
        if (!directory) {
            if (metadata.file_count == limits.max_files || size > limits.max_total_bytes - metadata.total_bytes) {
                throw StateError("SOURCE_TOO_LARGE", "The commit source exceeds server limits.");
            }
            ++metadata.file_count;
            metadata.total_bytes += size;
        }
        metadata.entries.push_back({fs::path(relative), directory ? WorkspaceEntryType::directory :
            WorkspaceEntryType::regular_file, size, {}});
        write_header(writer.get(), relative, size, directory);
        if (directory) continue;
        Sha256 digest;
        std::uint64_t total = 0;
        while (true) {
            const auto count = archive_read_data(reader.get(), buffer.data(), buffer.size());
            if (count < 0) fail("Could not read Git source data.");
            if (count == 0) break;
            const auto bytes = std::span(buffer.data(), static_cast<std::size_t>(count));
            digest.update(bytes);
            if (archive_write_data(writer.get(), bytes.data(), bytes.size()) != count) {
                fail("Could not write commit source data.");
            }
            total += static_cast<std::uint64_t>(count);
        }
        if (total != size) fail("Git source entry size changed while archiving.");
        files.push_back({relative, size, digest.finish()});
    }
    if (header_result != ARCHIVE_EOF || archive_read_close(reader.get()) != ARCHIVE_OK) {
        fail("Could not finish reading the Git source archive.");
    }
    if (input.pipe.wait() != 0) fail("Git archive failed while preparing the source.");
    close_writer(writer, output);
    std::ranges::sort(metadata.entries, {}, &SourceFileMetadata::relative_path);
    return {std::move(metadata), aggregate_hash(std::move(files)), archive_size(archive_path),
        input.total, input.largest};
}

} // namespace vibris::mcp
