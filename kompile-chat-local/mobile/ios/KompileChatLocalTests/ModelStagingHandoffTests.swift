import XCTest
@testable import KompileChatLocal

final class ModelStagingHandoffTests: XCTestCase {
    func testHuggingFaceReferenceUsesFragmentAndReservedQuery() throws {
        let url = try ModelStagingHandoff.build(
            baseURL: "https://staging.example/api",
            targetProfile: "ios-arm64-metal",
            huggingFaceReference: "owner/model"
        )
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.path, "/api/download")
        XCTAssertEqual(
            Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") }),
            [
                "target": "ios-arm64-metal",
                "artifact": "model",
                "source": "ios"
            ]
        )
        XCTAssertEqual(components.percentEncodedFragment, "hf=owner%2Fmodel")
    }

    func testRemoteHttpAndEveryExistingQueryAreRejected() {
        XCTAssertThrowsError(
            try ModelStagingHandoff.build(
                baseURL: "http://staging.example",
                targetProfile: "ios-arm64-metal",
                huggingFaceReference: "owner/model"
            )
        )
        XCTAssertThrowsError(
            try ModelStagingHandoff.build(
                baseURL: "https://staging.example?session=secret",
                targetProfile: "ios-arm64-metal",
                huggingFaceReference: "owner/model"
            )
        )
    }

    func testLoopbackHttpIsAvailableForSimulatorDevelopment() throws {
        let url = try ModelStagingHandoff.build(
            baseURL: "http://127.0.0.1:8080",
            targetProfile: "ios-arm64-metal",
            huggingFaceReference: "owner/model"
        )
        XCTAssertEqual(url.scheme, "http")
        XCTAssertEqual(url.host, "127.0.0.1")
    }

    func testDownloadPathComparisonUsesWholeFinalComponent() throws {
        let url = try ModelStagingHandoff.build(
            baseURL: "https://staging.example/notdownload",
            targetProfile: "ios-arm64-metal",
            huggingFaceReference: "owner/model"
        )
        XCTAssertEqual(url.path, "/notdownload/download")
    }

    func testAdvancedComponentsRequireCompleteTokenizerAndModelMetadata() {
        var bundle = ModelStagingHandoff.ComponentBundle()
        bundle.modelURL = "https://huggingface.co/owner/model/resolve/main/model.gguf"
        bundle.tokenizerURL = "https://huggingface.co/owner/model/resolve/main/tokenizer.json"

        XCTAssertThrowsError(
            try ModelStagingHandoff.build(
                baseURL: "https://staging.example",
                targetProfile: "ios-arm64-metal",
                components: bundle
            )
        )
    }
}
