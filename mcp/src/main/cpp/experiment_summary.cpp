#include "experiment_summary.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <map>
#include <numeric>
#include <sstream>
#include <set>
#include <vector>

namespace vibris::mcp {
namespace {
using Key = std::array<std::string, 4>;
double median(std::vector<double> values) {
    std::sort(values.begin(), values.end());
    const auto middle = values.size() / 2;
    return values.size() % 2 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
}
Json identity(const Key& key) {
    return {{"preset_id", key[0]}, {"source_id", key[1]}, {"config_id", key[2]}, {"metric_id", key[3]}};
}
std::string csv_quote(const std::string& value) {
    std::string result = "\"";
    for (const auto character : value) { if (character == '"') result += '"'; result += character; }
    return result + '"';
}
struct Measurements {
    std::vector<double> p50, p95, avg;
    std::map<int, std::vector<double>> rounds;
    std::string main_source;
};
}

Json summarize_experiment(const Json& cases, const Json& arguments) {
    std::map<Key, Measurements> measurements;
    std::map<std::array<std::string, 3>, Json> provenance;
    Json violations = Json::array();
    Json conflicts = Json::array();
    const auto statistic = arguments.value("delta_statistic", std::string("p50"));
    for (const auto& item : cases) {
        const auto preset = item.value("preset_id", std::string{});
        const auto source = item.value("source_id", std::string{});
        const auto config = item.value("config_id", std::string{});
        const auto proof = item.value("provenance", Json::object());
        Json fingerprint = Json::object();
        for (const auto* field : {"source_snapshot_sha256", "config_sha256", "preset_sha256", "scene_sha256",
            "pass_mapping_sha256", "environment"}) fingerprint[field] = proof.is_object() ? proof.value(field, Json(nullptr)) : Json(nullptr);
        const std::array<std::string, 3> case_key{preset, source, config};
        const auto [position, inserted] = provenance.emplace(case_key, fingerprint);
        if (!inserted) {
            for (const auto& [field, value] : fingerprint.items()) {
                if (position->second[field] != value) violations.push_back({{"preset_id", preset}, {"source_id", source},
                    {"config_id", config}, {"round", item.value("round", 0)}, {"run", item.value("run", 0)},
                    {"field", field}, {"expected", position->second[field]}, {"actual", value}});
            }
        }
        if (item.value("status", std::string{}) != "passed" || !item.contains("metrics") || !item["metrics"].is_object()) continue;
        std::map<std::string, Json> unique_metrics;
        std::set<std::string> conflicted;
        for (const auto& metric : item["metrics"].value("metrics", Json::array())) {
            const auto id = metric.at("metric_id").get<std::string>();
            const auto [found, inserted] = unique_metrics.emplace(id, metric);
            if (inserted) continue;
            bool equal = true;
            for (const auto* field : {"average_ns", "p50_ns", "p95_ns", "samples_ns"})
                equal = equal && found->second.value(field, Json(nullptr)) == metric.value(field, Json(nullptr));
            for (const auto* field : {"program_id", "pass_id", "main_source"}) {
                const auto previous = found->second.value(field, std::string{});
                const auto current = metric.value(field, std::string{});
                if (!previous.empty() && !current.empty() && previous != current) equal = false;
            }
            if (!equal) {
                if (conflicted.insert(id).second) conflicts.push_back({{"preset_id", preset},
                    {"source_id", source}, {"config_id", config}, {"metric_id", id},
                    {"round", item.value("round", 0)}, {"run", item.value("run", 0)},
                    {"reason", "Conflicting records for one metric in one run; excluded from statistics."}});
            } else if ((found->second.value("program_id", std::string{}).empty() &&
                !metric.value("program_id", std::string{}).empty()) ||
                (found->second.value("main_source", std::string{}).empty() &&
                !metric.value("main_source", std::string{}).empty())) found->second = metric;
        }
        for (const auto& [id, metric] : unique_metrics) {
            if (conflicted.contains(id)) continue;
            auto& data = measurements[{preset, source, config, metric.at("metric_id").get<std::string>()}];
            data.p50.push_back(metric.at("p50_ns").get<double>());
            data.p95.push_back(metric.at("p95_ns").get<double>());
            data.avg.push_back(metric.at("average_ns").get<double>());
            data.rounds[item.value("round", 0)].push_back(metric.at(statistic == "p50" ? "p50_ns" : "average_ns").get<double>());
            data.main_source = metric.value("main_source", std::string{});
        }
    }
    Json table = Json::array(), deltas = Json::array();
    for (const auto& [key, data] : measurements) {
        auto row = identity(key);
        row.update({{"runs", data.p50.size()}, {"p50_ns", median(data.p50)}, {"p95_ns", median(data.p95)},
            {"average_ns", median(data.avg)}, {"min_p50_ns", *std::min_element(data.p50.begin(), data.p50.end())},
            {"max_p50_ns", *std::max_element(data.p50.begin(), data.p50.end())}, {"main_source", data.main_source}});
        table.push_back(std::move(row));
        if (!arguments.contains("baseline")) continue;
        const auto& baseline = arguments["baseline"];
        const Key baseline_key{key[0], baseline.at("source_id").get<std::string>(), baseline.at("config_id").get<std::string>(), key[3]};
        if (key == baseline_key) continue;
        auto delta = identity(key);
        const auto found = measurements.find(baseline_key);
        std::vector<double> paired;
        Json missing = Json::array();
        for (int round = 0; round < arguments.value("rounds", 1); ++round) {
            const auto candidate = data.rounds.find(round);
            if (found == measurements.end() || candidate == data.rounds.end() || !found->second.rounds.contains(round) ||
                median(found->second.rounds.at(round)) == 0) { missing.push_back(round); continue; }
            paired.push_back(median(candidate->second) / median(found->second.rounds.at(round)) - 1);
        }
        delta["paired_rounds"] = paired.size();
        delta["missing_rounds"] = std::move(missing);
        delta["statistic"] = statistic;
        if (!paired.empty()) {
            delta["median"] = median(paired);
            delta["min"] = *std::min_element(paired.begin(), paired.end());
            delta["max"] = *std::max_element(paired.begin(), paired.end());
            const auto mean = std::accumulate(paired.begin(), paired.end(), 0.0) / paired.size();
            delta["mean"] = mean;
            if (paired.size() >= 3) {
                constexpr std::array critical{0.0, 12.7062047364, 4.3026527299, 3.1824463053, 2.7764451052,
                    2.5705818356, 2.4469118488, 2.3646242516, 2.3060041352, 2.2621571629};
                double squared = 0;
                for (const auto value : paired) squared += (value - mean) * (value - mean);
                const auto margin = critical.at(paired.size() - 1) * std::sqrt(squared / (paired.size() - 1) / paired.size());
                delta["ci95"] = {{"estimator", "mean_of_paired_round_deltas"}, {"lower", mean - margin}, {"upper", mean + margin}};
            }
        }
        deltas.push_back(std::move(delta));
    }
    return {{"table", std::move(table)}, {"delta_vs_baseline", std::move(deltas)}, {"metric_conflicts", std::move(conflicts)},
        {"consistency", {{"consistent", violations.empty()}, {"violations", std::move(violations)}}}};
}

std::string experiment_csv(const Json& cases) {
    std::ostringstream output;
    output.precision(17);
    output << "preset_id,round,run,source_id,config_id,metric_id,p50_ns,p95_ns,average_ns,main_source\n";
    for (const auto& item : cases) {
        if (!item.contains("metrics") || !item["metrics"].is_object()) continue;
        for (const auto& metric : item["metrics"].value("metrics", Json::array())) {
            output << csv_quote(item.value("preset_id", std::string{})) << ',' << item.value("round", 0) << ',' << item.value("run", 0)
                << ',' << csv_quote(item.value("source_id", std::string{})) << ',' << csv_quote(item.value("config_id", std::string{}))
                << ',' << csv_quote(metric.at("metric_id").get<std::string>()) << ',' << metric.at("p50_ns").get<double>()
                << ',' << metric.at("p95_ns").get<double>() << ',' << metric.at("average_ns").get<double>() << ','
                << csv_quote(metric.value("main_source", std::string{})) << '\n';
        }
    }
    return output.str();
}
}
