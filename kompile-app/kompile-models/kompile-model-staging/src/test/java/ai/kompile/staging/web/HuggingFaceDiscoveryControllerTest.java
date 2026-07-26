package ai.kompile.staging.web;

import ai.kompile.staging.download.HuggingFaceDiscovery;
import ai.kompile.staging.download.HuggingFaceDownloader;
import ai.kompile.staging.web.dto.HuggingFaceDiscoveryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HuggingFaceDiscoveryControllerTest {

    @Test
    void returnsSelectionResponseWithoutPersistingTokenInUrl() throws Exception {
        HuggingFaceDownloader downloader = mock(HuggingFaceDownloader.class);
        HuggingFaceDiscovery expected = HuggingFaceDiscovery.builder()
                .repository("owner/repo")
                .requestedRevision("main")
                .resolvedRevision("a".repeat(40))
                .requiresModelSelection(true)
                .build();
        when(downloader.discover(
                "https://huggingface.co/owner/repo", "main", "hf_secret"))
                .thenReturn(expected);
        HuggingFaceDiscoveryController controller =
                new HuggingFaceDiscoveryController(downloader);

        HuggingFaceDiscovery actual = controller.discover(
                HuggingFaceDiscoveryRequest.builder()
                        .reference("https://huggingface.co/owner/repo")
                        .revision("main")
                        .authToken("hf_secret")
                        .build());

        assertSame(expected, actual);
        verify(downloader).discover(
                "https://huggingface.co/owner/repo", "main", "hf_secret");
    }

    @Test
    void mapsMalformedReferencesToBadRequest() throws Exception {
        HuggingFaceDownloader downloader = mock(HuggingFaceDownloader.class);
        when(downloader.discover("bad", null, null))
                .thenThrow(new IllegalArgumentException("bad reference"));
        HuggingFaceDiscoveryController controller =
                new HuggingFaceDiscoveryController(downloader);

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> controller.discover(
                        HuggingFaceDiscoveryRequest.builder().reference("bad").build()));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
    }
}
