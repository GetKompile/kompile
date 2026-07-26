import Foundation

struct CanonicalArchiveCopy {
    let archivePath: String
    let installRoot: URL
}

/// Transactional, app-owned installation for canonical SDZ archives and manual local components.
struct ModelImportStore {
    private let fileManager: FileManager
    private let baseDirectory: URL

    init(
        fileManager: FileManager = .default,
        baseDirectory override: URL? = nil
    ) throws {
        self.fileManager = fileManager
        if let override {
            self.baseDirectory = override
        } else {
            let applicationSupport = try fileManager.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )
            self.baseDirectory = applicationSupport
                .appendingPathComponent("KompileChatLocal", isDirectory: true)
                .appendingPathComponent("ModelImports", isDirectory: true)
        }
        try fileManager.createDirectory(
            at: baseDirectory,
            withIntermediateDirectories: true
        )
    }

    var modelCacheDirectory: URL {
        get throws {
            let cache = baseDirectory.appendingPathComponent("SdxModelCache", isDirectory: true)
            try fileManager.createDirectory(at: cache, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var mutable = cache
            try? mutable.setResourceValues(values)
            return cache
        }
    }

    func copyCanonicalArchive(from source: URL) throws -> CanonicalArchiveCopy {
        guard source.pathExtension.lowercased() == "sdz" else {
            throw ModelImportError.invalid(
                "Canonical import accepts a compiled .sdz archive. Use manual components for GGUF/GGML."
            )
        }
        try requireNonemptyFile(source, label: "SDZ archive")

        let root = baseDirectory
            .appendingPathComponent("Canonical", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let partial = root.appendingPathExtension("partial")
        let destination = partial.appendingPathComponent("model.sdz")
        do {
            try fileManager.createDirectory(at: partial, withIntermediateDirectories: true)
            try fileManager.copyItem(at: source, to: destination)
            try fileManager.moveItem(at: partial, to: root)
            return CanonicalArchiveCopy(
                archivePath: root.appendingPathComponent("model.sdz").path,
                installRoot: root
            )
        } catch {
            try? fileManager.removeItem(at: partial)
            try? fileManager.removeItem(at: root)
            throw ModelImportError.invalid(
                "Could not copy the SDZ archive into app storage: \(error.localizedDescription)"
            )
        }
    }

    func installManualComponents(from selected: [URL], targetProfile: String) throws -> LocalModelBundle {
        let indexed = try indexManualComponents(selected)
        let root = baseDirectory
            .appendingPathComponent("Manual", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let partial = root.appendingPathExtension("partial")
        var committed = false
        defer {
            if !committed {
                try? fileManager.removeItem(at: partial)
                try? fileManager.removeItem(at: root)
            }
        }

        try fileManager.createDirectory(at: partial, withIntermediateDirectories: true)
        let modelExtension = indexed.model.pathExtension.lowercased()
        let model = partial.appendingPathComponent("model.\(modelExtension)")
        let tokenizer = partial.appendingPathComponent("tokenizer.json")
        let tokenizerConfig = partial.appendingPathComponent("tokenizer_config.json")

        try fileManager.copyItem(at: indexed.model, to: model)
        try fileManager.copyItem(at: indexed.tokenizer, to: tokenizer)
        try requireJsonObject(tokenizer, label: "tokenizer.json")

        var tokenizerObject: [String: Any] = [:]
        if let sourceConfig = indexed.tokenizerConfig {
            tokenizerObject = try readJsonObject(sourceConfig, label: "tokenizer_config.json")
        }
        if let sourceTemplate = indexed.chatTemplate {
            let template = try String(contentsOf: sourceTemplate, encoding: .utf8)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !template.isEmpty else {
                throw ModelImportError.invalid("chat_template.jinja is empty.")
            }
            tokenizerObject["chat_template"] = template
            try fileManager.copyItem(
                at: sourceTemplate,
                to: partial.appendingPathComponent("chat_template.jinja")
            )
        }
        guard let chatTemplate = tokenizerObject["chat_template"] as? String,
              !chatTemplate.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ModelImportError.invalid(
                "tokenizer_config.json must contain a non-empty chat_template, or include chat_template.jinja."
            )
        }
        let normalizedTokenizerConfig = try JSONSerialization.data(
            withJSONObject: tokenizerObject,
            options: [.sortedKeys, .prettyPrinted]
        )
        try normalizedTokenizerConfig.write(to: tokenizerConfig, options: .atomic)

        var modelConfig: URL?
        if let source = indexed.modelConfig {
            modelConfig = partial.appendingPathComponent("config.json")
            try fileManager.copyItem(at: source, to: modelConfig!)
            try requireJsonObject(modelConfig!, label: "config.json")
        }
        var textGeneration: URL?
        if let source = indexed.textGeneration {
            textGeneration = partial.appendingPathComponent("text-generation.json")
            try fileManager.copyItem(at: source, to: textGeneration!)
            try requireJsonObject(textGeneration!, label: "text-generation.json")
        }
        var generationConfig: URL?
        if let source = indexed.generationConfig {
            generationConfig = partial.appendingPathComponent("generation_config.json")
            try fileManager.copyItem(at: source, to: generationConfig!)
            try requireJsonObject(generationConfig!, label: "generation_config.json")
        }

        try fileManager.moveItem(at: partial, to: root)
        let bundle = LocalModelBundle(
            sourceKind: .manualComponents,
            targetProfile: targetProfile,
            modelPath: root.appendingPathComponent("model.\(modelExtension)").path,
            tokenizerPath: root.appendingPathComponent("tokenizer.json").path,
            tokenizerConfigPath: root.appendingPathComponent("tokenizer_config.json").path,
            textGenerationConfigPath: textGeneration.map {
                root.appendingPathComponent($0.lastPathComponent).path
            },
            modelConfigPath: modelConfig.map {
                root.appendingPathComponent($0.lastPathComponent).path
            },
            chatTemplatePath: indexed.chatTemplate.map { _ in
                root.appendingPathComponent("chat_template.jinja").path
            },
            generationConfigPath: generationConfig.map {
                root.appendingPathComponent($0.lastPathComponent).path
            }
        )
        _ = try bundle.validated(fileManager: fileManager)
        try writeManifest(bundle, to: root.appendingPathComponent("model-import.json"))
        committed = true
        return bundle
    }

    func persistActiveManifest(_ bundle: LocalModelBundle) throws -> URL {
        let manifests = baseDirectory.appendingPathComponent("Manifests", isDirectory: true)
        try fileManager.createDirectory(at: manifests, withIntermediateDirectories: true)
        let destination = manifests.appendingPathComponent(UUID().uuidString + ".json")
        try writeManifest(bundle, to: destination)
        return destination
    }

    func removeInstallRoot(_ url: URL) {
        try? fileManager.removeItem(at: url)
    }

    private func writeManifest(_ bundle: LocalModelBundle, to destination: URL) throws {
        let data = try JSONEncoder.sortedPretty.encode(bundle)
        try data.write(to: destination, options: .atomic)
    }

    private func indexManualComponents(_ selected: [URL]) throws -> RequiredManualComponents {
        var result = ManualComponents()
        for url in selected {
            let name = url.lastPathComponent.lowercased()
            switch name {
            case "tokenizer.json":
                try assignUnique(&result.tokenizer, url, label: "tokenizer.json")
            case "tokenizer_config.json":
                try assignUnique(&result.tokenizerConfig, url, label: "tokenizer_config.json")
            case "config.json":
                try assignUnique(&result.modelConfig, url, label: "config.json")
            case "chat_template.jinja":
                try assignUnique(&result.chatTemplate, url, label: "chat_template.jinja")
            case "generation_config.json":
                try assignUnique(&result.generationConfig, url, label: "generation_config.json")
            case "text-generation.json":
                try assignUnique(&result.textGeneration, url, label: "text-generation.json")
            default:
                let ext = url.pathExtension.lowercased()
                if ext == "gguf" || ext == "ggml" {
                    try assignUnique(&result.model, url, label: "GGUF/GGML model")
                }
            }
        }
        guard result.model != nil else {
            throw ModelImportError.invalid("Select exactly one .gguf or .ggml model.")
        }
        guard result.tokenizer != nil else {
            throw ModelImportError.invalid("Select tokenizer.json.")
        }
        guard result.tokenizerConfig != nil || result.chatTemplate != nil else {
            throw ModelImportError.invalid(
                "Select tokenizer_config.json or chat_template.jinja."
            )
        }
        guard result.modelConfig != nil || result.textGeneration != nil else {
            throw ModelImportError.invalid("Select config.json or text-generation.json.")
        }
        return result.required()
    }

    private func assignUnique(_ slot: inout URL?, _ value: URL, label: String) throws {
        guard slot == nil else {
            throw ModelImportError.invalid("Select only one \(label) file.")
        }
        slot = value
    }

    private func requireNonemptyFile(_ url: URL, label: String) throws {
        var directory: ObjCBool = false
        guard fileManager.fileExists(atPath: url.path, isDirectory: &directory),
              !directory.boolValue,
              let attributes = try? fileManager.attributesOfItem(atPath: url.path),
              let size = attributes[.size] as? NSNumber,
              size.int64Value > 0 else {
            throw ModelImportError.invalid("\(label) is missing or empty.")
        }
    }

    private func requireJsonObject(_ url: URL, label: String) throws {
        _ = try readJsonObject(url, label: label)
    }

    private func readJsonObject(_ url: URL, label: String) throws -> [String: Any] {
        try requireNonemptyFile(url, label: label)
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        let object = try JSONSerialization.jsonObject(with: data)
        guard let dictionary = object as? [String: Any] else {
            throw ModelImportError.invalid("\(label) must contain a JSON object.")
        }
        return dictionary
    }
}

private struct ManualComponents {
    var model: URL?
    var tokenizer: URL?
    var tokenizerConfig: URL?
    var modelConfig: URL?
    var chatTemplate: URL?
    var generationConfig: URL?
    var textGeneration: URL?

    func required() -> RequiredManualComponents {
        RequiredManualComponents(
            model: model!,
            tokenizer: tokenizer!,
            tokenizerConfig: tokenizerConfig,
            modelConfig: modelConfig,
            chatTemplate: chatTemplate,
            generationConfig: generationConfig,
            textGeneration: textGeneration
        )
    }
}

private struct RequiredManualComponents {
    let model: URL
    let tokenizer: URL
    let tokenizerConfig: URL?
    let modelConfig: URL?
    let chatTemplate: URL?
    let generationConfig: URL?
    let textGeneration: URL?
}

private extension JSONEncoder {
    static var sortedPretty: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return encoder
    }
}

enum ModelImportError: LocalizedError {
    case invalid(String)

    var errorDescription: String? {
        switch self {
        case .invalid(let message):
            return message
        }
    }
}
