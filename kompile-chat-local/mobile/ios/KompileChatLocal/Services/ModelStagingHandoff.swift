import Foundation

/// Builds an external-Safari handoff. Source values stay in the URI fragment and are
/// never persisted by the app or sent in the staging HTTP request.
enum ModelStagingHandoff {
    struct ComponentBundle {
        var modelURL = ""
        var tokenizerURL = ""
        var tokenizerConfigURL = ""
        var modelConfigURL = ""
        var chatTemplateURL = ""
        var generationConfigURL = ""
        var textGenerationURL = ""
    }

    static func build(
        baseURL: String,
        targetProfile: String,
        huggingFaceReference: String? = nil,
        components: ComponentBundle? = nil
    ) throws -> URL {
        var base = try stagingBase(baseURL)
        let target = targetProfile.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !target.isEmpty else {
            throw ModelStagingHandoffError.invalid(
                "A target profile is required for model staging."
            )
        }

        let reference = try normalizeHuggingFaceReference(huggingFaceReference)
        let componentFields = try components.map(normalizeComponentBundle)
        guard reference == nil || componentFields == nil else {
            throw ModelStagingHandoffError.invalid(
                "Choose Hugging Face discovery or Advanced component URLs, not both."
            )
        }

        var query = base.queryItems ?? []
        query.append(URLQueryItem(name: "target", value: target))
        query.append(URLQueryItem(name: "artifact", value: "model"))
        query.append(URLQueryItem(name: "source", value: "ios"))
        base.queryItems = query

        if let reference {
            base.percentEncodedFragment = "hf=" + encodeFragment(reference)
        } else if let componentFields {
            base.percentEncodedFragment = componentFields
                .map { encodeFragment($0.key) + "=" + encodeFragment($0.value) }
                .joined(separator: "&")
        }
        guard let result = base.url else {
            throw ModelStagingHandoffError.invalid("Could not construct the staging URL.")
        }
        return result
    }

    static func normalizeHuggingFaceReference(_ raw: String?) throws -> String? {
        let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !value.isEmpty else { return nil }
        if matchesRepositoryID(value) {
            return value
        }
        guard let components = URLComponents(string: value),
              components.scheme?.lowercased() == "https",
              let host = components.host?.lowercased(),
              host == "huggingface.co" || host == "www.huggingface.co",
              components.port == nil,
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil,
              canonicalPath(components.percentEncodedPath) else {
            throw ModelStagingHandoffError.invalid(
                "Enter owner/repository or a canonical public Hugging Face HTTPS URL without credentials, query, or fragment."
            )
        }

        let segments = components.percentEncodedPath
            .split(separator: "/")
            .map(String.init)
        guard segments.count >= 2,
              matchesRepositoryID(segments[0] + "/" + segments[1]) else {
            throw ModelStagingHandoffError.invalid(
                "The Hugging Face URL must identify an owner and repository."
            )
        }
        if segments.count > 2 {
            let route = segments[2]
            let supported = ["tree", "blob", "resolve"].contains(route)
            let minimum = route == "tree" ? 4 : 5
            guard supported, segments.count >= minimum else {
                throw ModelStagingHandoffError.invalid(
                    "Use a repository URL or a complete tree/blob/resolve Hugging Face URL."
                )
            }
        }
        return "https://huggingface.co/" + segments.joined(separator: "/")
    }

    private static func normalizeComponentBundle(
        _ bundle: ComponentBundle
    ) throws -> [(key: String, value: String)] {
        let model = try publicComponentURL(bundle.modelURL, label: "GGUF/GGML model", required: true)!
        let modelPath = URL(string: model)?.path.lowercased() ?? ""
        guard modelPath.hasSuffix(".gguf") || modelPath.hasSuffix(".ggml") else {
            throw ModelStagingHandoffError.invalid(
                "The model URL must end in .gguf or .ggml."
            )
        }
        let tokenizer = try publicComponentURL(
            bundle.tokenizerURL,
            label: "tokenizer.json",
            required: true
        )!
        let tokenizerConfig = try publicComponentURL(
            bundle.tokenizerConfigURL,
            label: "tokenizer_config.json",
            required: false
        )
        let chatTemplate = try publicComponentURL(
            bundle.chatTemplateURL,
            label: "chat_template.jinja",
            required: false
        )
        guard tokenizerConfig != nil || chatTemplate != nil else {
            throw ModelStagingHandoffError.invalid(
                "Provide tokenizer_config.json or chat_template.jinja."
            )
        }
        let modelConfig = try publicComponentURL(
            bundle.modelConfigURL,
            label: "config.json",
            required: false
        )
        let textGeneration = try publicComponentURL(
            bundle.textGenerationURL,
            label: "text-generation.json",
            required: false
        )
        guard modelConfig != nil || textGeneration != nil else {
            throw ModelStagingHandoffError.invalid(
                "Provide config.json or text-generation.json."
            )
        }
        let generationConfig = try publicComponentURL(
            bundle.generationConfigURL,
            label: "generation_config.json",
            required: false
        )

        var fields: [(String, String)] = [
            ("modelUrl", model),
            ("tokenizerUrl", tokenizer)
        ]
        if let tokenizerConfig { fields.append(("tokenizerConfigUrl", tokenizerConfig)) }
        if let chatTemplate { fields.append(("chatTemplateUrl", chatTemplate)) }
        if let modelConfig { fields.append(("modelConfigUrl", modelConfig)) }
        if let textGeneration { fields.append(("textGenerationUrl", textGeneration)) }
        if let generationConfig { fields.append(("generationConfigUrl", generationConfig)) }
        return fields
    }

    private static func publicComponentURL(
        _ raw: String,
        label: String,
        required: Bool
    ) throws -> String? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty {
            if required {
                throw ModelStagingHandoffError.invalid("\(label) URL is required.")
            }
            return nil
        }
        guard let components = URLComponents(string: value),
              components.scheme?.lowercased() == "https",
              let host = components.host?.lowercased(),
              !host.isEmpty,
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil,
              !components.percentEncodedPath.isEmpty,
              canonicalPath(components.percentEncodedPath) else {
            throw ModelStagingHandoffError.invalid(
                "\(label) must be a public canonical HTTPS URL without credentials, query, fragment, encoded path segments, or path traversal."
            )
        }
        var canonical = components
        canonical.scheme = "https"
        canonical.host = host
        guard let result = canonical.url?.absoluteString else {
            throw ModelStagingHandoffError.invalid("\(label) URL is invalid.")
        }
        return result
    }

    private static func stagingBase(_ raw: String) throws -> URLComponents {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else {
            throw ModelStagingHandoffError.invalid(
                "Configure a Kompile model staging server URL first."
            )
        }
        guard var components = URLComponents(string: value),
              let scheme = components.scheme?.lowercased(),
              let host = components.host?.lowercased(),
              !host.isEmpty,
              scheme == "https" || (scheme == "http" && isLoopback(host)),
              components.user == nil,
              components.password == nil,
              components.percentEncodedQuery == nil,
              components.fragment == nil,
              canonicalPath(components.percentEncodedPath) else {
            throw ModelStagingHandoffError.invalid(
                "The staging server must use HTTPS (HTTP is allowed only for loopback development) and must not contain credentials, a query, a fragment, encoded path segments, or path traversal."
            )
        }
        components.scheme = scheme
        components.host = host
        let root = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let alreadyDownload = root.split(separator: "/").last == "download"
        components.path = root.isEmpty
            ? "/download"
            : (alreadyDownload ? "/" + root : "/" + root + "/download")
        return components
    }

    private static func matchesRepositoryID(_ value: String) -> Bool {
        value.range(
            of: "^[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}$",
            options: .regularExpression
        ) != nil
    }

    private static func isLoopback(_ host: String) -> Bool {
        host == "localhost" || host == "127.0.0.1" || host == "::1"
    }

    private static func canonicalPath(_ path: String) -> Bool {
        guard !path.contains("%"), !path.contains("\\"), !path.contains("//") else {
            return false
        }
        return !path.split(separator: "/", omittingEmptySubsequences: false)
            .contains(where: { $0 == "." || $0 == ".." })
    }

    private static func encodeFragment(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }
}

enum ModelStagingHandoffError: LocalizedError {
    case invalid(String)

    var errorDescription: String? {
        switch self {
        case .invalid(let message):
            return message
        }
    }
}
