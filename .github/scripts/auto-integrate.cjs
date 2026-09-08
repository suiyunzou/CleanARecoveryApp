// Only invoked after Release build, tests and APK verification succeed.
module.exports = async function integrate({ github, context, core }) {
  const { owner, repo } = context.repo;
  const branch = context.ref.replace(/^refs\/heads\//, '');
  if (!['push', 'workflow_dispatch'].includes(context.eventName)
      || !context.ref.startsWith('refs/heads/codex/')) return;
  const api = github.rest;
  const getHead = async () => (await api.repos.getBranch({ owner, repo, branch })).data.commit.sha;
  const dispatch = async ref => api.actions.createWorkflowDispatch({
    owner, repo, workflow_id: 'android.yml', ref
  });
  if (await getHead() !== context.sha) {
    core.notice('A newer commit exists; only its successful build may integrate.');
    return;
  }
  const comparison = (await api.repos.compareCommitsWithBasehead({
    owner, repo, basehead: `main...${context.sha}`
  })).data;
  if (comparison.ahead_by === 0) {
    // Retry after a merge succeeded but dispatch failed.
    const merged = (await api.pulls.list({ owner, repo, state: 'closed',
      base: 'main', head: `${owner}:${branch}`, per_page: 100 })).data
      .find(pr => pr.merged_at && pr.head.sha === context.sha);
    if (merged) await dispatch('main');
    return;
  }
  let pr = (await api.pulls.list({ owner, repo, state: 'open',
    base: 'main', head: `${owner}:${branch}`, per_page: 100 })).data[0];
  if (!pr) {
    const commit = (await api.repos.getCommit({ owner, repo, ref: context.sha })).data;
    pr = (await api.pulls.create({ owner, repo, base: 'main', head: branch,
      title: commit.commit.message.split('\n')[0].slice(0, 200),
      body: `自动集成开发分支 \`${branch}\`。\n\nRelease 构建、单元测试及 APK 验证已通过：${context.serverUrl}/${owner}/${repo}/actions/runs/${context.runId}\n\n测试提交：\`${context.sha}\`。main 有新提交时先同步并重新验证，合并成功后自动启动 main 发布。`
    })).data;
  }
  const number = pr.number;
  pr = (await api.pulls.get({ owner, repo, pull_number: number })).data;
  if (pr.draft || pr.head.sha !== context.sha) throw new Error('PR is a draft or its head changed.');
  const reviews = await github.paginate(api.pulls.listReviews, { owner, repo, pull_number: number });
  const latest = new Map();
  for (const review of reviews) {
    if (['APPROVED', 'CHANGES_REQUESTED', 'DISMISSED'].includes(review.state)) latest.set(review.user.login, review.state);
  }
  if ([...latest.values()].includes('CHANGES_REQUESTED')) throw new Error('A reviewer requested changes.');
  let cursor = null;
  do {
    const result = await github.graphql(`query($owner:String!,$repo:String!,$number:Int!,$cursor:String) {
      repository(owner:$owner,name:$repo) { pullRequest(number:$number) {
        reviewThreads(first:100,after:$cursor) { nodes { isResolved } pageInfo { hasNextPage endCursor } }
      } }
    }`, { owner, repo, number, cursor });
    const threads = result.repository.pullRequest.reviewThreads;
    if (threads.nodes.some(t => !t.isResolved)) throw new Error('Unresolved review comments require attention.');
    cursor = threads.pageInfo.hasNextPage ? threads.pageInfo.endCursor : null;
  } while (cursor);
  // Re-read main after review queries, since another PR may have merged.
  const fresh = (await api.repos.compareCommitsWithBasehead({
    owner, repo, basehead: `main...${context.sha}`
  })).data;
  if (fresh.behind_by > 0) {
    await api.pulls.updateBranch({ owner, repo, pull_number: number, expected_head_sha: context.sha });
    // GITHUB_TOKEN updates do not reliably start another push build.
    await dispatch(branch);
    core.notice('Updated from main; a fresh Release build must pass before merging.');
    return;
  }
  if (pr.mergeable === false) throw new Error('Merge conflicts require attention.');
  if (await getHead() !== context.sha) throw new Error('Branch changed during integration; waiting for its new build.');
  const result = await api.pulls.merge({ owner, repo, pull_number: number,
    sha: context.sha, merge_method: 'merge' });
  if (!result.data.merged) throw new Error(result.data.message || 'GitHub refused the merge.');
  await dispatch('main');
  await core.summary.addHeading(`PR #${number} merged`).addRaw('main Release build and publication dispatched.').write();
};
