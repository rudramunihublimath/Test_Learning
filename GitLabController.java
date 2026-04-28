package com.example.gitlab.controller;

import com.example.gitlab.model.CloneResult;
import com.example.gitlab.model.GitLabConfig;
import com.example.gitlab.service.GitLabApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitLabController - REST endpoints to trigger Clone / README / Push operations.
 *
 * Deployed as JAR on Azure Web App.
 * Config values read from Azure App Settings (environment variables).
 */
@RestController
@RequestMapping("/api/gitlab")
public class GitLabController {

    private static final Logger log = LoggerFactory.getLogger(GitLabController.class);

    @Autowired
    private GitLabApiService gitLabApiService;

    // Injected from application.properties or Azure App Settings
    @Value("${gitlab.base-url}")
    private String gitLabBaseUrl;

    @Value("${gitlab.access-token}")
    private String accessToken;

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/gitlab/sync
    // Single endpoint: clone → create README → push
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full pipeline: Clone feature branch → Create README.md → Push back to GitLab.
     *
     * Request body:
     * {
     *   "projectPath": "mygroup/myproject",
     *   "branch":      "feature/my-feature"
     * }
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncRepository(
            @RequestBody Map<String, String> request) {

        String projectPath = request.get("projectPath");
        String branch      = request.get("branch");

        if (projectPath == null || branch == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Both 'projectPath' and 'branch' are required."
            ));
        }

        GitLabConfig config = new GitLabConfig(gitLabBaseUrl, projectPath, branch, accessToken);
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // ── Step 1: Clone ────────────────────────────────────────────────
            log.info("Step 1: Cloning repository '{}/{}' ...", projectPath, branch);
            CloneResult cloneResult = gitLabApiService.cloneRepository(config);

            result.put("step1_clone",     "SUCCESS");
            result.put("clonedPath",      cloneResult.getClonedPath());
            result.put("headCommitSha",   cloneResult.getCommitSha());

            // ── Step 2: Create README ────────────────────────────────────────
            log.info("Step 2: Creating README.md at '{}'", cloneResult.getClonedPath());
            Path readmePath = gitLabApiService.createReadme(cloneResult.getClonedPath(), config);

            result.put("step2_readme",    "SUCCESS");
            result.put("readmePath",      readmePath.toAbsolutePath().toString());

            // ── Step 3: Push ─────────────────────────────────────────────────
            log.info("Step 3: Pushing README.md back to GitLab branch '{}'", branch);
            String pushResponse = gitLabApiService.pushChanges(cloneResult.getClonedPath(), config);

            result.put("step3_push",      "SUCCESS");
            result.put("gitLabResponse",  pushResponse);

            log.info("Pipeline completed successfully.");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Pipeline failed", e);
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Individual step endpoints (useful for testing / partial runs)
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/clone")
    public ResponseEntity<Map<String, Object>> cloneOnly(
            @RequestBody Map<String, String> request) throws Exception {

        GitLabConfig config = buildConfig(request);
        CloneResult result  = gitLabApiService.cloneRepository(config);

        return ResponseEntity.ok(Map.of(
                "status",       "SUCCESS",
                "clonedPath",   result.getClonedPath(),
                "headCommitSha",result.getCommitSha()
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("GitLab API Service is running on Azure Web App.");
    }

    // ── private ───────────────────────────────────────────────────────────────

    private GitLabConfig buildConfig(Map<String, String> request) {
        return new GitLabConfig(
                gitLabBaseUrl,
                request.get("projectPath"),
                request.get("branch"),
                accessToken
        );
    }
}
