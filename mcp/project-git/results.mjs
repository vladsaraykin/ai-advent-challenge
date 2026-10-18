// Only errors proved to occur before publishing a file/commit/remote mutation are recoverable.
// Transport errors and ambiguous filesystem/Git/GitHub failures remain hard failures.
export const rejectedBeforeMutation = error => new Set([
  'path_not_allowed', 'write_path_not_allowed', 'file_not_found', 'verification_failed',
  'invalid_content', 'invalid_edits', 'invalid_paths', 'invalid_offset', 'invalid_query', 'invalid_prefix',
  'edit_must_match_exactly_once', 'stale_file_read_again', 'file_too_large_or_not_regular', 'file_not_text',
  'file_too_large_for_safe_replacement', 'result_too_large_read_fewer_files',
  'invalid_workspace', 'workspace_required', 'workspace_not_created', 'workspace_identity_mismatch',
  'unsafe_workspace_directory', 'workspace_too_large', 'stale_workspace_review_diff_again',
  'invalid_commit_message', 'nothing_to_commit', 'head_changed_or_workspace_dirty', 'remote_not_allowed',
  'invalid_pr', 'push_exact_commit_first', 'github_account_not_allowed', 'check_not_allowed'
]).has(error.message);
