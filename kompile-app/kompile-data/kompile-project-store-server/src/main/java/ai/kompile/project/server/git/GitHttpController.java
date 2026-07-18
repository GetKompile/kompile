package ai.kompile.project.server.git;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

/**
 * Serves the Git Smart-HTTP protocol for hosted projects at {@code /git/{namespace}/{slug}.git/...},
 * enabling {@code git clone} / {@code fetch} / {@code push}. Implemented directly on JGit core
 * ({@link UploadPack} / {@link ReceivePack}) driven over the request/response streams, so it does not
 * depend on JGit's {@code javax.servlet}-based GitServlet (incompatible with Spring Boot's jakarta
 * servlet container).
 *
 * <p>Large data tracked by git-xet is materialized separately through the Xet CAS endpoints; this
 * serves the repository skeleton, source, the {@code kompile.project.json} manifest and pointer files.
 */
@RestController
public class GitHttpController {

    private static final String PREFIX = "/git/";

    private final GitRepoService gitRepoService;

    public GitHttpController(GitRepoService gitRepoService) {
        this.gitRepoService = gitRepoService;
    }

    @GetMapping("/git/**")
    public void handleGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String sub = gitSubPath(req);
        if (sub.endsWith("/info/refs")) {
            String repoPath = sub.substring(0, sub.length() - "/info/refs".length());
            infoRefs(repoPath, req.getParameter("service"), resp);
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    @PostMapping("/git/**")
    public void handlePost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String sub = gitSubPath(req);
        if (sub.endsWith("/git-upload-pack")) {
            rpc(sub.substring(0, sub.length() - "/git-upload-pack".length()), "git-upload-pack", req, resp);
        } else if (sub.endsWith("/git-receive-pack")) {
            rpc(sub.substring(0, sub.length() - "/git-receive-pack".length()), "git-receive-pack", req, resp);
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    private void infoRefs(String repoPath, String service, HttpServletResponse resp) throws IOException {
        String[] ns = parseRepo(repoPath);
        if (ns == null || !gitRepoService.repoExists(ns[0], ns[1])) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!"git-upload-pack".equals(service) && !"git-receive-pack".equals(service)) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "only smart HTTP is supported");
            return;
        }
        resp.setContentType("application/x-" + service + "-advertisement");
        resp.setHeader("Cache-Control", "no-cache");
        try (Repository repo = gitRepoService.openRepo(ns[0], ns[1])) {
            OutputStream out = resp.getOutputStream();
            PacketLineOut pck = new PacketLineOut(out);
            pck.writeString("# service=" + service + "\n");
            pck.end();
            RefAdvertiser.PacketLineOutRefAdvertiser adv = new RefAdvertiser.PacketLineOutRefAdvertiser(pck);
            if ("git-upload-pack".equals(service)) {
                UploadPack up = new UploadPack(repo);
                up.setBiDirectionalPipe(false);
                up.sendAdvertisedRefs(adv);
            } else {
                ReceivePack rp = new ReceivePack(repo);
                rp.setBiDirectionalPipe(false);
                rp.sendAdvertisedRefs(adv);
            }
        }
    }

    private void rpc(String repoPath, String service, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String[] ns = parseRepo(repoPath);
        if (ns == null || !gitRepoService.repoExists(ns[0], ns[1])) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        resp.setContentType("application/x-" + service + "-result");
        InputStream in = decodeBody(req);
        try (Repository repo = gitRepoService.openRepo(ns[0], ns[1])) {
            if ("git-upload-pack".equals(service)) {
                UploadPack up = new UploadPack(repo);
                up.setBiDirectionalPipe(false);
                up.upload(in, resp.getOutputStream(), null);
            } else {
                ReceivePack rp = new ReceivePack(repo);
                rp.setBiDirectionalPipe(false);
                rp.receive(in, resp.getOutputStream(), null);
            }
        }
    }

    private static InputStream decodeBody(HttpServletRequest req) throws IOException {
        InputStream in = req.getInputStream();
        return "gzip".equalsIgnoreCase(req.getHeader("Content-Encoding")) ? new GZIPInputStream(in) : in;
    }

    private static String gitSubPath(HttpServletRequest req) {
        String uri = req.getRequestURI();
        int idx = uri.indexOf(PREFIX);
        return idx >= 0 ? uri.substring(idx + PREFIX.length()) : "";
    }

    /** Parse {@code namespace/slug.git} into {namespace, slug}, or null if malformed. */
    private static String[] parseRepo(String repoPath) {
        String p = repoPath;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.endsWith(".git")) {
            p = p.substring(0, p.length() - 4);
        }
        String[] parts = p.split("/");
        if (parts.length != 2 || !HostedProjectNames.isValidIdentifier(parts[0])
                || !HostedProjectNames.isValidIdentifier(parts[1])) {
            return null;
        }
        return new String[]{parts[0], parts[1]};
    }
}
