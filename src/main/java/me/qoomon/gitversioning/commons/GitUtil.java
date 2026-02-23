package me.qoomon.gitversioning.commons;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.Constants.R_TAGS;

public final class GitUtil {

    public static String NO_COMMIT = "0000000000000000000000000000000000000000";

    public static Status status(Repository repository) {
        try {
            return Git.wrap(repository).status().call();
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        } catch (NoWorkTreeException e) {
            // In a worktree, JGit may not recognise the work tree.
            // Re-open the repository via the common dir with the worktree path set explicitly.
            try {
                File workTree = worktreesFix_getWorkTree(repository);
                File commonDirFile = new File(repository.getDirectory(), "commondir");
                if (commonDirFile.exists()) {
                    String commonDirPath = Files.readAllLines(commonDirFile.toPath()).get(0);
                    File commonGitDir = new File(repository.getDirectory(), commonDirPath);
                    try (Repository worktreeRepo = new FileRepositoryBuilder()
                            .setGitDir(commonGitDir)
                            .setWorkTree(workTree)
                            .build()) {
                        return Git.wrap(worktreeRepo).status().call();
                    }
                }
                throw new RuntimeException(e);
            } catch (IOException | GitAPIException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public static String branch(Repository repository) throws IOException {
        String branch = repository.getBranch();
        if (ObjectId.isId(branch)) {
            return null;
        }
        return branch;
    }

    public static List<String> tag_pointsAt(Repository repository, String revstr) throws IOException {
        ObjectId revObjectId = repository.resolve(revstr);
        List<String> tagNames = new ArrayList<>();
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(R_TAGS)) {
            Ref peeledRef = repository.getRefDatabase().peel(ref);
            ObjectId targetObjectId = peeledRef.getPeeledObjectId() != null
                    ? peeledRef.getPeeledObjectId()
                    : peeledRef.getObjectId();
            if (targetObjectId.equals(revObjectId)) {
                String tagName = ref.getName().replaceFirst("^" + R_TAGS, "");
                tagNames.add(tagName);
            }
        }
        return tagNames;
    }

    public static String revParse(Repository repository, String revstr) throws IOException {
        ObjectId rev = repository.resolve(revstr);
        if (rev == null) {
            return NO_COMMIT;
        }
        return rev.getName();
    }

    public static long revTimestamp(Repository repository, String revstr) throws IOException {
        ObjectId rev = repository.resolve(revstr);
        if (rev == null) {
            return 0;
        }
        // The timestamp is expressed in seconds since epoch...
        return repository.parseCommit(rev).getCommitTime();
    }

    public static GitSituation situation(File directory) throws IOException {
        FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder().findGitDir(directory);
        if (repositoryBuilder.getGitDir() == null) {
            return null;
        }
        try (Repository repository = repositoryBuilder.build()) {
            Repository commonRepo = worktreesFix_getCommonRepository(repository);
            File rootDirectory = worktreesFix_getWorkTree(repository);
            ObjectId headObjectId = worktreesFix_resolveHead(repository);
            String headCommit = headObjectId != null ? headObjectId.getName() : NO_COMMIT;
            long headCommitTimestamp = headObjectId != null ? commonRepo.parseCommit(headObjectId).getCommitTime() : 0;
            String headBranch = GitUtil.branch(repository);
            List<String> headTags = GitUtil.tag_pointsAt(commonRepo, HEAD);
            boolean isClean = GitUtil.status(repository).isClean();
            return new GitSituation(rootDirectory, headCommit, headCommitTimestamp, headBranch, headTags, isClean);
        }
    }

    /**
     * Resolve the work tree directory, handling git worktrees where
     * {@link Repository#getWorkTree()} throws {@link NoWorkTreeException}.
     *
     * @see Repository#getWorkTree()
     */
    public static File worktreesFix_getWorkTree(Repository repository) throws IOException {
        try {
            return repository.getWorkTree();
        } catch (NoWorkTreeException e) {
            // In a worktree, the git directory (e.g. .git/worktrees/MahiMain3) contains
            // a "gitdir" file pointing back to the worktree's .git file
            File gitDirFile = new File(repository.getDirectory(), "gitdir");
            if (gitDirFile.exists()) {
                String gitDirPath = Files.readAllLines(gitDirFile.toPath()).get(0);
                return new File(gitDirPath).getParentFile();
            }
            throw e;
        }
    }

    /**
     * Get the common repository for a worktree. In a linked worktree, the object database
     * and refs live in the common git directory, not the worktree-specific one.
     * For normal repositories, returns the same repository.
     *
     * @return the common repository (may be a new instance for worktrees)
     */
    public static Repository worktreesFix_getCommonRepository(Repository repository) throws IOException {
        try {
            repository.getWorkTree();
            return repository;
        } catch (NoWorkTreeException e) {
            File commonDirFile = new File(repository.getDirectory(), "commondir");
            if (!commonDirFile.exists()) {
                throw e;
            }

            String commonDirPath = Files.readAllLines(commonDirFile.toPath()).get(0);
            File commonGitDir = new File(repository.getDirectory(), commonDirPath);
            return new FileRepositoryBuilder().setGitDir(commonGitDir).build();
        }
    }

    /**
     * Resolve HEAD in a worktree-safe way. In a linked worktree, JGit may fail
     * to resolve HEAD because it doesn't recognise the worktree git directory.
     * This method falls back to manually parsing the HEAD file.
     *
     * @see Repository#resolve(String)
     */
    public static ObjectId worktreesFix_resolveHead(Repository repository) throws IOException {
        try {
            repository.getWorkTree();
            return repository.resolve(HEAD);
        } catch (NoWorkTreeException e) {
            File headFile = new File(repository.getDirectory(), "HEAD");
            if (!headFile.exists()) {
                throw e;
            }

            String head = Files.readAllLines(headFile.toPath()).get(0);
            if (head.startsWith("ref:")) {
                String refPath = head.replaceFirst("^ref: *", "");

                File commonDirFile = new File(repository.getDirectory(), "commondir");
                String commonDirPath = Files.readAllLines(commonDirFile.toPath()).get(0);
                File commonGitDir = new File(repository.getDirectory(), commonDirPath);

                File refFile = new File(commonGitDir, refPath);
                head = Files.readAllLines(refFile.toPath()).get(0);
            }
            return repository.resolve(head);
        }
    }
}