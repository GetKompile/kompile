import XCTest
@testable import KompileChatLocal

final class ModelImportStoreTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("ModelImportStoreTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let root {
            try? FileManager.default.removeItem(at: root)
        }
        root = nil
    }

    func testManualComponentsInstallACompleteTokenizerOwnedBundle() throws {
        let source = root.appendingPathComponent("source", isDirectory: true)
        try FileManager.default.createDirectory(at: source, withIntermediateDirectories: true)
        let model = try write(Data([0x47, 0x47, 0x55, 0x46]), named: "research.gguf", under: source)
        let tokenizer = try writeJson(["version": "1.0"], named: "tokenizer.json", under: source)
        let tokenizerConfig = try writeJson(
            ["chat_template": "{% for message in messages %}{{ message.content }}{% endfor %}"],
            named: "tokenizer_config.json",
            under: source
        )
        let config = try writeJson(["model_type": "llama"], named: "config.json", under: source)

        let store = try ModelImportStore(
            fileManager: .default,
            baseDirectory: root.appendingPathComponent("store", isDirectory: true)
        )
        let bundle = try store.installManualComponents(
            from: [model, tokenizer, tokenizerConfig, config],
            targetProfile: "ios-arm64-metal"
        )

        XCTAssertEqual(bundle.sourceKind, .manualComponents)
        XCTAssertEqual(bundle.targetProfile, "ios-arm64-metal")
        XCTAssertTrue(FileManager.default.fileExists(atPath: bundle.modelPath))
        XCTAssertTrue(FileManager.default.fileExists(atPath: bundle.tokenizerPath))
        XCTAssertTrue(FileManager.default.fileExists(atPath: bundle.tokenizerConfigPath))
        XCTAssertNotNil(bundle.modelConfigPath)
        XCTAssertNoThrow(try bundle.validated())
        let normalized = try JSONSerialization.jsonObject(
            with: Data(contentsOf: URL(fileURLWithPath: bundle.tokenizerConfigPath))
        ) as? [String: Any]
        XCTAssertNotNil(normalized?["chat_template"] as? String)
    }

    func testInvalidTokenizerRollsBackPartialInstall() throws {
        let source = root.appendingPathComponent("invalid-source", isDirectory: true)
        try FileManager.default.createDirectory(at: source, withIntermediateDirectories: true)
        let model = try write(Data([0x47]), named: "research.gguf", under: source)
        let tokenizer = try write(Data("[]".utf8), named: "tokenizer.json", under: source)
        let tokenizerConfig = try writeJson(
            ["chat_template": "{{ messages }}"],
            named: "tokenizer_config.json",
            under: source
        )
        let config = try writeJson(["model_type": "llama"], named: "config.json", under: source)
        let base = root.appendingPathComponent("rollback-store", isDirectory: true)
        let store = try ModelImportStore(fileManager: .default, baseDirectory: base)

        XCTAssertThrowsError(
            try store.installManualComponents(
                from: [model, tokenizer, tokenizerConfig, config],
                targetProfile: "ios-arm64-metal"
            )
        )
        let manual = base.appendingPathComponent("Manual", isDirectory: true)
        let leftovers = (try? FileManager.default.contentsOfDirectory(
            at: manual,
            includingPropertiesForKeys: nil
        )) ?? []
        XCTAssertTrue(leftovers.isEmpty)
    }

    func testCanonicalPathRejectsLooseGguf() throws {
        let loose = try write(Data([0x47]), named: "model.gguf", under: root)
        let store = try ModelImportStore(
            fileManager: .default,
            baseDirectory: root.appendingPathComponent("canonical-store", isDirectory: true)
        )
        XCTAssertThrowsError(try store.copyCanonicalArchive(from: loose))
    }

    private func write(_ data: Data, named name: String, under directory: URL) throws -> URL {
        let destination = directory.appendingPathComponent(name)
        try data.write(to: destination, options: .atomic)
        return destination
    }

    private func writeJson(
        _ object: [String: Any],
        named name: String,
        under directory: URL
    ) throws -> URL {
        try write(
            JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
            named: name,
            under: directory
        )
    }
}
