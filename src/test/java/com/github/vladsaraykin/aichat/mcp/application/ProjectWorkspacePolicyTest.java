package com.github.vladsaraykin.aichat.mcp.application;

import com.github.vladsaraykin.aichat.agent.application.ChatFailure;
import com.github.vladsaraykin.aichat.harness.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectWorkspacePolicyTest {
    static RequestContext context(String owner,List<String> servers,boolean files) {
        return new RequestContext(owner,"assistant",UUID.randomUUID(),UUID.randomUUID(),files ? "/files Task" : "Task",
                new ChatSettings(0,false,servers,null),null,null,null,false,null,false,files);
    }
    @Test void onlyVladMayMutateAndOnlyWithBothSelectedServersInFileMode() {
        for(var tool:List.of("project_apply_edits","project_write_document","project_delete_file","project_workspace_create",
                "project_git_commit","project_git_push","project_git_create_pr","project_run_checks")) {
            var server=tool.startsWith("project_git") || tool.equals("project_workspace_create") || tool.equals("project_run_checks") ? "project-git" : "project-files";
            assertThatThrownBy(()->ProjectWorkspacePolicy.authorize(context("alice",List.of("project-files","project-git"),true),server,tool))
                    .isInstanceOf(ChatFailure.class).hasMessageContaining("vlad");
            assertThatThrownBy(()->ProjectWorkspacePolicy.authorize(context("vlad",List.of("project-files"),true),server,tool))
                    .isInstanceOf(ChatFailure.class).hasMessageContaining("project-git");
            assertThatCode(()->ProjectWorkspacePolicy.authorize(context("vlad",List.of("project-files","project-git"),true),server,tool)).doesNotThrowAnyException();
        }
        assertThatThrownBy(()->ProjectWorkspacePolicy.authorize(context("alice",List.of("filesystem"),false),"filesystem","write_file"))
                .hasMessageContaining("vlad");
    }
    @Test void publicationsAndDeletionCannotBeGrantedPermanentlyAndReadsRemainVersionBound() {
        assertThat(ProjectWorkspacePolicy.oneShot("project-files","project_delete_file")).isTrue();
        assertThat(ProjectWorkspacePolicy.oneShot("project-git","project_git_push")).isTrue();
        assertThat(ProjectWorkspacePolicy.oneShot("project-git","project_git_create_pr")).isTrue();
        assertThat(ProjectWorkspacePolicy.oneShot("project-git","project_git_commit")).isFalse();
        assertThat(ProjectWorkspacePolicy.mutates("project_read_files")).isFalse();
        assertThat(ProjectWorkspacePolicy.scoped(context("vlad",List.of("project-files","project-git"),true),"project-files","project_read_files")).isTrue();
        assertThat(ProjectWorkspacePolicy.scoped(context("vlad",List.of("project-git"),false),"project-git","get_current_branch")).isFalse();
        assertThat(ProjectWorkspacePolicy.available(context("vlad",List.of("project-files","project-git"),true),"project-git","project_git_push")).isTrue();
        assertThat(ProjectWorkspacePolicy.available(context("alice",List.of("project-files","project-git"),true),"project-git","project_git_push")).isFalse();
        assertThat(ProjectWorkspacePolicy.available(context("vlad",List.of("project-git"),false),"project-git","project_git_push")).isFalse();
        assertThat(ProjectWorkspacePolicy.scoped(context("alice",List.of("project-files","project-git"),true),"project-files","project_read_files")).isFalse();
    }
}
