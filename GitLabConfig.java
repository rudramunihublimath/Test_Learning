package com.example.gitlab.model;

/**
 * GitLabConfig - holds all connection parameters for a GitLab operation.
 * Populate from application.properties / Azure App Settings (env vars).
 */
public class GitLabConfig {

    private String gitLabBaseUrl;   // e.g. https://gitlab.com
    private String projectPath;     // e.g. mygroup/myproject  (namespace/project)
    private String branch;          // e.g. feature/my-feature
    private String accessToken;     // GitLab Personal Access Token (PAT) or CI job token

    // ── constructors ──────────────────────────────────────────────────────────

    public GitLabConfig() {}

    public GitLabConfig(String gitLabBaseUrl, String projectPath,
                        String branch, String accessToken) {
        this.gitLabBaseUrl = gitLabBaseUrl;
        this.projectPath   = projectPath;
        this.branch        = branch;
        this.accessToken   = accessToken;
    }

    // ── getters / setters ─────────────────────────────────────────────────────

    public String getGitLabBaseUrl() { return gitLabBaseUrl; }
    public void setGitLabBaseUrl(String gitLabBaseUrl) { this.gitLabBaseUrl = gitLabBaseUrl; }

    public String getProjectPath()   { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }

    public String getBranch()        { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getAccessToken()   { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
}
