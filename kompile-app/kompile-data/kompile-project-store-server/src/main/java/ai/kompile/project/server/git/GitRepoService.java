package ai.kompile.project.server.git;

import ai.kompile.project.server.ProjectStoreServerProperties;
import jakarta.annotation.PostConstruct;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Manages the on-disk bare git repositories backing hosted projects. Repos live under
 * {@code <dataDir>/repos/<namespace>/<slug>.git}. Also provides tree/blob browsing for the project API.
 */
@Service
public class GitRepoService {

    private static final Logger LOG = LoggerFactory.getLogger(GitRepoService.class);

    private final File reposBase;

    public GitRepoService(ProjectStoreServerProperties props) {
        this.reposBase = Paths.get(props.getDataDir(), "repos").toFile();
    }

    @PostConstruct
    void ensureBaseDir() {
        if (!reposBase.exists() && !reposBase.mkdirs()) {
            LOG.warn("Could not create git repos base dir: {}", reposBase);
        }
    }

    public File repoDir(String namespace, String slug) {
        return new File(new File(reposBase, namespace), slug + ".git");
    }

    public boolean repoExists(String namespace, String slug) {
        return repoDir(namespace, slug).isDirectory();
    }

    /** Initialize a bare repo for a project if it does not already exist. */
    public File initBareRepo(String namespace, String slug, String defaultBranch) {
        File dir = repoDir(namespace, slug);
        if (dir.exists()) {
            return dir;
        }
        File parent = dir.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create repo parent dir: " + parent);
        }
        try (Git ignored = Git.init().setBare(true).setDirectory(dir)
                .setInitialBranch(defaultBranch != null ? defaultBranch : "main").call()) {
            LOG.info("Initialized bare repo {}/{} at {}", namespace, slug, dir);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init bare repo " + namespace + "/" + slug, e);
        }
        return dir;
    }

    public Repository openRepo(String namespace, String slug) throws IOException {
        return new FileRepositoryBuilder().setGitDir(repoDir(namespace, slug)).build();
    }

    public static class TreeEntry {
        public String name;
        public String path;
        public String type; // "blob" or "tree"
        public long size;
    }

    /** List entries directly under {@code path} (null/empty = root) at the given ref. */
    public List<TreeEntry> listTree(Repository repo, String ref, String path) throws IOException {
        List<TreeEntry> out = new ArrayList<>();
        ObjectId commitId = repo.resolve(ref == null ? Constants.HEAD : ref);
        if (commitId == null) {
            return out;
        }
        boolean root = (path == null || path.isEmpty());
        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit commit = rw.parseCommit(commitId);
            RevTree tree = commit.getTree();
            try (TreeWalk tw = new TreeWalk(repo)) {
                if (root) {
                    tw.addTree(tree);
                } else {
                    try (TreeWalk sub = TreeWalk.forPath(repo, path, tree)) {
                        if (sub == null || !sub.isSubtree()) {
                            return out;
                        }
                        tw.addTree(sub.getObjectId(0));
                    }
                }
                tw.setRecursive(false);
                while (tw.next()) {
                    TreeEntry e = new TreeEntry();
                    e.name = tw.getNameString();
                    e.path = root ? e.name : path + "/" + e.name;
                    if (tw.isSubtree()) {
                        e.type = "tree";
                    } else {
                        e.type = "blob";
                        ObjectLoader ol = repo.open(tw.getObjectId(0));
                        e.size = ol.getSize();
                    }
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** Read a file blob at the given ref and path, or null if not found. */
    public byte[] getBlob(Repository repo, String ref, String path) throws IOException {
        ObjectId commitId = repo.resolve(ref == null ? Constants.HEAD : ref);
        if (commitId == null) {
            return null;
        }
        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit commit = rw.parseCommit(commitId);
            try (TreeWalk tw = TreeWalk.forPath(repo, path, commit.getTree())) {
                if (tw == null) {
                    return null;
                }
                return repo.open(tw.getObjectId(0)).getBytes();
            }
        }
    }

    /**
     * Stream every file of the tree at {@code ref} into a ZIP written to {@code out}. Walks the tree
     * recursively and copies each blob entry without buffering the whole repo in memory. Returns false
     * if the ref cannot be resolved (e.g. an empty repo). The underlying stream is finished but not
     * closed, so the caller (servlet container) retains ownership.
     */
    public boolean writeArchive(Repository repo, String ref, OutputStream out) throws IOException {
        ObjectId commitId = repo.resolve(ref == null ? Constants.HEAD : ref);
        if (commitId == null) {
            return false;
        }
        ZipOutputStream zip = new ZipOutputStream(out);
        try (RevWalk rw = new RevWalk(repo); TreeWalk tw = new TreeWalk(repo)) {
            RevCommit commit = rw.parseCommit(commitId);
            tw.addTree(commit.getTree());
            tw.setRecursive(true);
            while (tw.next()) {
                ObjectLoader loader = repo.open(tw.getObjectId(0));
                ZipEntry entry = new ZipEntry(tw.getPathString());
                entry.setSize(loader.getSize());
                zip.putNextEntry(entry);
                loader.copyTo(zip);
                zip.closeEntry();
            }
        }
        zip.finish();
        return true;
    }
}
