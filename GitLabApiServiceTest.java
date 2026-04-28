package com.example.gitlab.service;

import com.example.gitlab.model.GitLabConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GitLabApiService.
 *
 * Integration tests (requiring a real GitLab token) are skipped by default.
 * Set GITLAB_INTEGRATION_TEST=true to run them.
 */
@SpringBootTest
class GitLabApiServiceTest {

    private final GitLabApiService service = new GitLabApiService();

    /**
     * Integration test — clone a public GitLab repo.
     * Set env: GITLAB_INTEGRATION_TEST=true to activate.
     */
    @Test
    void testClonePublicRepo() throws Exception {
        String runIntegration = System.getenv("GITLAB_INTEGRATION_TEST");
        if (!"true".equalsIgnoreCase(runIntegration)) {
            System.out.println("Skipping integration test. Set GITLAB_INTEGRATION_TEST=true to run.");
            return;
        }

        GitLabConfig config = new GitLabConfig(
                "https://gitlab.com",
                "gitlab-org/gitlab-foss",  // public repo — use your own for private
                "master",
                System.getenv("GITLAB_ACCESS_TOKEN")
        );

        var result = service.cloneRepository(config);

        assertNotNull(result.getClonedPath());
        assertTrue(Files.exists(Paths.get(result.getClonedPath())));
        assertFalse(result.getCommitSha().isBlank());

        System.out.println("Cloned to: " + result.getClonedPath());
        System.out.println("HEAD SHA : " + result.getCommitSha());
    }

    @Test
    void testCreateReadme() throws Exception {
        // Create a temp dir to simulate cloned path
        Path tempDir = Files.createTempDirectory("test-clone-");

        GitLabConfig config = new GitLabConfig(
                "https://gitlab.com",
                "mygroup/myproject",
                "feature/test-branch",
                "dummy-token"
        );

        Path readme = service.createReadme(tempDir.toString(), config);

        assertTrue(Files.exists(readme));
        String content = Files.readString(readme);
        assertTrue(content.contains("mygroup/myproject"));
        assertTrue(content.contains("feature/test-branch"));

        System.out.println("README created at: " + readme);
        System.out.println("Content preview:\n" + content.substring(0, Math.min(300, content.length())));
    }
}
