package com.example.gitlab.model;

/**
 * CloneResult - returned by GitLabApiService.cloneRepository().
 * Contains the local path of the extracted repo and the HEAD commit SHA.
 */
public class CloneResult {

    private final String clonedPath;   // Absolute local path of extracted repository
    private final String commitSha;    // HEAD commit SHA of the branch

    public CloneResult(String clonedPath, String commitSha) {
        this.clonedPath = clonedPath;
        this.commitSha  = commitSha;
    }

    public String getClonedPath() { return clonedPath; }
    public String getCommitSha()  { return commitSha;  }

    @Override
    public String toString() {
        return "CloneResult{clonedPath='" + clonedPath + "', commitSha='" + commitSha + "'}";
    }
}
