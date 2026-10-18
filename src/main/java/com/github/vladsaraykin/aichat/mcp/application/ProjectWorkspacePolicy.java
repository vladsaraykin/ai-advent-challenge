package com.github.vladsaraykin.aichat.mcp.application;

import com.github.vladsaraykin.aichat.agent.application.ChatFailure;
import com.github.vladsaraykin.aichat.harness.domain.RequestContext;
import java.util.Set;

/** Authority is enforced by backend, not model input, annotations or UI visibility. */
public final class ProjectWorkspacePolicy {
    private ProjectWorkspacePolicy() { }
    private static final Set<String> READS=Set.of("project_list_files","project_search_text","project_read_files",
            "project_preview_document","project_preview_edits","get_current_branch","list_project_files","project_git_status","project_git_remote_status");
    private static final Set<String> WRITES=Set.of("project_write_document","project_apply_edits","project_create_file",
            "project_rename_file","project_delete_file","project_workspace_create","project_git_commit",
            "project_git_push","project_git_create_pr","project_run_checks");
    public static boolean project(String server) { return server.equals("project-files") || server.equals("project-git"); }
    public static boolean oneShot(String server,String tool) {
        return project(server) && Set.of("project_delete_file","project_git_push","project_git_create_pr").contains(tool);
    }
    public static boolean mutates(String tool) { return WRITES.contains(tool); }
    public static boolean scoped(RequestContext context,String server,String tool) {
        return project(server) && (READS.contains(tool) || WRITES.contains(tool))
                && (context.owner().equals("vlad") && context.fileOperations() && context.settings().mcpServerIds().contains("project-git") || WRITES.contains(tool));
    }
    public static boolean available(RequestContext context,String server,String tool) {
        if(context==null) return true; // Legacy calls still require an approval context at execution.
        if(server.equals("filesystem") && !context.owner().equals("vlad")) return false;
        if(!project(server)) return true;
        if(WRITES.contains(tool) || tool.equals("project_git_status") || tool.equals("project_git_remote_status"))
            return context.owner().equals("vlad") && context.fileOperations()
                    && context.settings().mcpServerIds().containsAll(Set.of("project-files","project-git"));
        return true;
    }
    public static void authorize(RequestContext context,String server,String tool) {
        if (server.equals("filesystem") && !context.owner().equals("vlad"))
            throw new ChatFailure(ChatFailure.Kind.INVALID,"Файловый MCP доступен только профилю vlad.");
        if (project(server) && WRITES.contains(tool)) {
            if (!context.owner().equals("vlad")) throw new ChatFailure(ChatFailure.Kind.INVALID,"Запись проекта и операции GitHub доступны только профилю vlad.");
            if (!context.fileOperations() || !context.settings().mcpServerIds().containsAll(Set.of("project-files","project-git")))
                throw new ChatFailure(ChatFailure.Kind.INVALID,"Для изменения проекта используйте /files и выберите project-files и project-git.");
        }
    }
}
