package com.github.vladsaraykin.aichat.mcp.application;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectWorkspaceStoreTest {
    @Test void backendOverridesForgedWorkspaceAndVersionAndBindsReadCacheToRevision() {
        var jdbc=mock(JdbcTemplate.class);var store=spy(new ProjectWorkspaceStore(jdbc));
        var context=ProjectWorkspacePolicyTest.context("vlad",List.of("project-files","project-git"),true);
        doReturn(Optional.of(new ProjectWorkspaceStore.View(context.chatId(),8,Map.of()))).when(store)
                .find("vlad","assistant",context.chatId());
        var json=JsonMapper.builder().build();
        var read=json.readTree(store.bind(context,"project_read_files","{\"paths\":[\"README.md\"],\"workspaceId\":\"forged\",\"workspaceRevision\":0}"));
        assertThat(read.path("workspaceId").asString()).isEqualTo(context.chatId().toString());
        assertThat(read.path("workspaceRevision").asInt()).isEqualTo(8);
        var write=json.readTree(store.bind(context,"project_apply_edits","{\"workspaceRevision\":900}"));
        assertThat(write.has("workspaceRevision")).isFalse();
    }
    @Test void recordsSpringAiTextContentEnvelopeAndPreservesPublicationAndCheckMetadata() {
        var jdbc=mock(JdbcTemplate.class);var store=new ProjectWorkspaceStore(jdbc);
        var context=ProjectWorkspacePolicyTest.context("vlad",List.of("project-files","project-git"),true);
        var json=JsonMapper.builder().build();
        String payload=json.writeValueAsString(Map.of("workspaceState",Map.of("id",context.chatId().toString(),"branch","ai/task-demo"),
                "prUrl","https://github.com/vladsaraykin/ai-advent-challenge/pull/123"));
        store.record(context,"project_git_create_pr",json.writeValueAsString(List.of(Map.of("type","text","text",payload))));
        verify(jdbc).update(contains("state=state ||"),contains("prUrl"),eq(1),eq(context.chatId()),eq("vlad"),eq("assistant"));
        assertThatThrownBy(()->store.record(context,"project_create_file","{\"saved\":true}"))
                .isInstanceOf(IllegalStateException.class);
        var alien=json.writeValueAsString(Map.of("workspaceState",Map.of("id",UUID.randomUUID().toString())));
        assertThatThrownBy(()->store.record(context,"project_create_file",alien)).isInstanceOf(IllegalStateException.class);
    }
    @Test void knownPreconditionRejectionInvalidatesReadsWithoutClaimingAWrite() {
        var jdbc=mock(JdbcTemplate.class);var store=new ProjectWorkspaceStore(jdbc);
        var context=ProjectWorkspacePolicyTest.context("vlad",List.of("project-files","project-git"),true);
        store.record(context,"project_apply_edits","{\"operationRejected\":true,\"saved\":false,\"error\":\"stale_file_read_again\"}");
        verify(jdbc).update(startsWith("UPDATE rag.project_workspaces SET revision=revision+1"),eq(context.chatId()),eq("vlad"),eq("assistant"));
    }
    @Test void publicationMetadataUsesApprovedShaRatherThanAManuallyMovedLocalHead() {
        var jdbc=mock(JdbcTemplate.class);var store=new ProjectWorkspaceStore(jdbc);
        var context=ProjectWorkspacePolicyTest.context("vlad",List.of("project-files","project-git"),true);
        var json=JsonMapper.builder().build();
        store.record(context,"project_git_push",json.writeValueAsString(Map.of("workspaceState",Map.of(
                "id",context.chatId().toString(),"headSha","b".repeat(40)),"pushed",true,"pushedHeadSha","a".repeat(40))));
        verify(jdbc).update(contains("state=state ||"),contains("\"pushedHeadSha\":\""+"a".repeat(40)),eq(1),eq(context.chatId()),eq("vlad"),eq("assistant"));
    }
}
