package com.example.gitlab.service;

import com.example.gitlab.model.GitLabConfig;
import com.example.gitlab.model.CloneResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * GitLabApiService - performs Clone / README creation / Push
 * entirely via GitLab REST API (no Git CLI).
 *
 * Compatible with Azure Web App (JAR deployment).
 */
@Service
public class GitLabApiService {

    private static final Logger log = LoggerFactory.getLogger(GitLabApiService.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GitLabApiService() {
        this.httpClient  = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. CLONE  — downloads the branch as a ZIP archive via GitLab API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "Clones" a GitLab repository branch by downloading it as a ZIP archive
     * via the GitLab Repository Archive API, then extracting it locally.
     *
     * @param config GitLab connection details
     * @return CloneResult containing the local extraction path + branch SHA
     */
    public CloneResult cloneRepository(GitLabConfig config) throws Exception {

        String encodedProject = URLEncoder.encode(config.getProjectPath(), StandardCharsets.UTF_8);
        String encodedBranch  = URLEncoder.encode(config.getBranch(),      StandardCharsets.UTF_8);

        // GitLab Archive API: GET /projects/:id/repository/archive
        String archiveUrl = String.format(
                "%s/api/v4/projects/%s/repository/archive?sha=%s&format=zip",
                config.getGitLabBaseUrl(), encodedProject, encodedBranch
        );

        log.info("Downloading archive from: {}", archiveUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(archiveUrl))
                .header("PRIVATE-TOKEN", config.getAccessToken())
                .header("Accept", "application/octet-stream")
                .GET()
                .build();

        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("GitLab archive API failed [" + response.statusCode() + "]: " + body);
        }

        // Extract to temp directory
        Path extractRoot = Files.createTempDirectory("gitlab-clone-");
        extractZip(response.body(), extractRoot);

        // GitLab archive produces a single root folder inside ZIP — find it
        Path repoRoot = Files.list(extractRoot)
                .filter(Files::isDirectory)
                .findFirst()
                .orElse(extractRoot);

        // Fetch the current branch HEAD commit SHA (needed for push)
        String commitSha = getLatestCommitSha(config);

        log.info("Repository extracted to: {}", repoRoot.toAbsolutePath());
        log.info("HEAD commit SHA: {}", commitSha);

        return new CloneResult(repoRoot.toAbsolutePath().toString(), commitSha);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. README  — creates README.md in the local clone output path
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates a README.md at the root of the cloned repository.
     *
     * @param clonedPath  absolute path returned by cloneRepository()
     * @param config      GitLab config (used for metadata in README)
     * @return Path to the created README.md
     */
    public Path createReadme(String clonedPath, GitLabConfig config) throws IOException {

        Path readmePath = Paths.get(clonedPath, "README.md");

        String content = buildReadmeContent(config);
        Files.writeString(readmePath, content, StandardCharsets.UTF_8);

        log.info("README.md created at: {}", readmePath.toAbsolutePath());
        return readmePath;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. PUSH  — commits the README via GitLab Commits API (no Git CLI)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pushes README.md back to GitLab using the Repository Files API.
     * Checks if the file already exists and uses CREATE or UPDATE accordingly.
     *
     * @param clonedPath  local path of the README.md
     * @param config      GitLab connection details
     * @return GitLab API response body
     */
    public String pushChanges(String clonedPath, GitLabConfig config) throws Exception {

        Path readmePath = Paths.get(clonedPath, "README.md");
        String readmeContent = Files.readString(readmePath, StandardCharsets.UTF_8);
        String encodedContent = Base64.getEncoder().encodeToString(
                readmeContent.getBytes(StandardCharsets.UTF_8));

        String encodedProject  = URLEncoder.encode(config.getProjectPath(), StandardCharsets.UTF_8);
        String encodedFilePath = URLEncoder.encode("README.md",             StandardCharsets.UTF_8);

        boolean fileExists = checkFileExists(config, "README.md");

        String filesUrl = String.format(
                "%s/api/v4/projects/%s/repository/files/%s",
                config.getGitLabBaseUrl(), encodedProject, encodedFilePath
        );

        // Build JSON payload
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("branch",         config.getBranch());
        payload.put("content",        encodedContent);
        payload.put("encoding",       "base64");
        payload.put("commit_message", "chore: add/update README.md via GitLab API [automated]");

        String jsonBody = objectMapper.writeValueAsString(payload);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(filesUrl))
                .header("PRIVATE-TOKEN", config.getAccessToken())
                .header("Content-Type",  "application/json");

        HttpRequest request = fileExists
                ? requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(jsonBody)).build()
                : requestBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

        log.info("{} README.md to branch '{}'", fileExists ? "Updating" : "Creating", config.getBranch());

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        if (status == 200 || status == 201) {
            log.info("Push successful. Response: {}", response.body());
            return response.body();
        } else {
            throw new RuntimeException("GitLab push failed [" + status + "]: " + response.body());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /** Fetch the latest commit SHA for the configured branch */
    private String getLatestCommitSha(GitLabConfig config) throws Exception {

        String encodedProject = URLEncoder.encode(config.getProjectPath(), StandardCharsets.UTF_8);
        String encodedBranch  = URLEncoder.encode(config.getBranch(),      StandardCharsets.UTF_8);

        String branchUrl = String.format(
                "%s/api/v4/projects/%s/repository/branches/%s",
                config.getGitLabBaseUrl(), encodedProject, encodedBranch
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(branchUrl))
                .header("PRIVATE-TOKEN", config.getAccessToken())
                .GET()
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode root = objectMapper.readTree(response.body());
        return root.path("commit").path("id").asText("unknown");
    }

    /** Checks if a file already exists in the repository on the given branch */
    private boolean checkFileExists(GitLabConfig config, String filePath) {
        try {
            String encodedProject  = URLEncoder.encode(config.getProjectPath(), StandardCharsets.UTF_8);
            String encodedFilePath = URLEncoder.encode(filePath,                StandardCharsets.UTF_8);
            String encodedBranch   = URLEncoder.encode(config.getBranch(),      StandardCharsets.UTF_8);

            String url = String.format(
                    "%s/api/v4/projects/%s/repository/files/%s?ref=%s",
                    config.getGitLabBaseUrl(), encodedProject, encodedFilePath, encodedBranch
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", config.getAccessToken())
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            return response.statusCode() == 200;

        } catch (Exception e) {
            log.warn("Could not check file existence, assuming new file. Error: {}", e.getMessage());
            return false;
        }
    }

    /** Extracts a ZIP InputStream into the given target directory */
    private void extractZip(InputStream zipStream, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();

                // Zip-slip protection
                if (!entryPath.startsWith(targetDir)) {
                    throw new SecurityException("ZIP entry escapes target dir: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream out = Files.newOutputStream(entryPath)) {
                        zis.transferTo(out);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /** Builds README.md content */
    private String buildReadmeContent(GitLabConfig config) {
        return String.format("""
                # %s
                
                > Auto-generated by **GitLab API Java Service** on Azure Web App
                
                ## Repository Details
                
                | Property      | Value                     |
                |---------------|---------------------------|
                | Project       | `%s`                      |
                | Branch        | `%s`                      |
                | GitLab Host   | `%s`                      |
                | Generated At  | `%s`                      |
                
                ## Overview
                
                This README was created programmatically using the GitLab REST API —
                no Git CLI was used. The service runs as a deployable JAR on
                **Azure App Service**.
                
                ## Tech Stack
                
                - Java 17+
                - Spring Boot 3.x
                - GitLab REST API v4
                - Azure Web App (JAR deployment)
                
                ## API Operations Used
                
                | Operation | GitLab API Endpoint                              |
                |-----------|--------------------------------------------------|
                | Clone     | `GET /repository/archive?sha=branch&format=zip`  |
                | File Check| `HEAD /repository/files/:file_path`              |
                | Push      | `POST/PUT /repository/files/:file_path`          |
                
                ---
                *This file is managed by automation. Manual edits may be overwritten.*
                """,
                deriveProjectName(config.getProjectPath()),
                config.getProjectPath(),
                config.getBranch(),
                config.getGitLabBaseUrl(),
                java.time.Instant.now().toString()
        );
    }

    private String deriveProjectName(String projectPath) {
        String[] parts = projectPath.split("/");
        return parts[parts.length - 1];
    }
}
