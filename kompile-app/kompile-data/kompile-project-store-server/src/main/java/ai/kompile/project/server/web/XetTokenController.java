package ai.kompile.project.server.web;

import ai.kompile.project.server.ProjectStoreServerProperties;
import ai.kompile.project.server.xet.XetTokenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hugging Face-compatible Xet "Hub" token endpoint. A client first requests a short-lived,
 * repo+ref+scope-scoped CAS token, then uses it against the CAS service (whose base URL is returned
 * here). The URL shape mirrors the HF Hub so unmodified hf_xet/git-xet clients can target this server:
 * <pre>GET /api/{models|datasets|spaces}/{namespace}/{slug}/xet-{read|write}-token/{revision}</pre>
 */
@RestController
@RequestMapping("/api")
public class XetTokenController {

    private final XetTokenService tokenService;
    private final ProjectStoreServerProperties props;

    public XetTokenController(XetTokenService tokenService, ProjectStoreServerProperties props) {
        this.tokenService = tokenService;
        this.props = props;
    }

    @GetMapping("/{repoType:models|datasets|spaces}/{namespace}/{slug}/xet-read-token/{revision}")
    public XetResponses.XetTokenResponse readToken(@PathVariable String repoType,
                                                   @PathVariable String namespace,
                                                   @PathVariable String slug,
                                                   @PathVariable String revision) {
        return mint(namespace, slug, revision, "read");
    }

    @GetMapping("/{repoType:models|datasets|spaces}/{namespace}/{slug}/xet-write-token/{revision}")
    public XetResponses.XetTokenResponse writeToken(@PathVariable String repoType,
                                                    @PathVariable String namespace,
                                                    @PathVariable String slug,
                                                    @PathVariable String revision) {
        return mint(namespace, slug, revision, "write");
    }

    private XetResponses.XetTokenResponse mint(String namespace, String slug, String revision, String scope) {
        long exp = System.currentTimeMillis() / 1000L + tokenService.ttlSeconds();
        String token = tokenService.mint(namespace + "/" + slug, revision, scope, exp);
        return new XetResponses.XetTokenResponse(token, exp, props.getCasUrl());
    }
}
