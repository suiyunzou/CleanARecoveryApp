const test = require('node:test');
const assert = require('node:assert/strict');
const integrate = require('./auto-integrate.cjs');

function fixture(options = {}) {
  const calls = [];
  const pr = { number: 9, draft: false, mergeable: true, head: { sha: 'tested' }, ...options.pr };
  const comparison = { ahead_by: 1, behind_by: 0, ...options.comparison };
  const context = { repo: { owner: 'owner', repo: 'repo' }, ref: 'refs/heads/codex/test',
    sha: 'tested', eventName: 'push', serverUrl: 'https://github.com', runId: 10, ...options.context };
  let reads = 0;
  const github = {
    rest: {
      repos: {
        getBranch: async () => ({ data: { commit: { sha: options.moving && reads++ > 0 ? 'new' : options.head || 'tested' } } }),
        compareCommitsWithBasehead: async () => ({ data: comparison }),
        getCommit: async () => ({ data: { commit: { message: 'Test change\n\nDescription' } } })
      },
      pulls: {
        list: async args => ({ data: args.state === 'closed' ? options.closed || [] : options.existing ? [pr] : [] }),
        create: async args => { calls.push(['create', args]); return { data: pr }; },
        get: async () => ({ data: pr }),
        listReviews: async () => {},
        updateBranch: async args => { calls.push(['update', args]); if (options.conflict) throw new Error('conflict'); },
        merge: async args => { calls.push(['merge', args]); return { data: { merged: !options.refused } }; }
      },
      actions: { createWorkflowDispatch: async args => { calls.push(['dispatch', args]); } }
    },
    paginate: async () => options.reviews || [],
    graphql: async () => ({ repository: { pullRequest: { reviewThreads: {
      nodes: options.unresolved ? [{ isResolved: false }] : [], pageInfo: { hasNextPage: false }
    } } } })
  };
  const summary = { addHeading() { return this; }, addRaw() { return this; }, async write() {} };
  return { calls, args: { github, context, core: { notice() {}, summary } } };
}

test('creates PR, merges exactly the tested SHA and dispatches main', async () => {
  const f = fixture(); await integrate(f.args);
  assert.deepEqual(f.calls.map(c => c[0]), ['create', 'merge', 'dispatch']);
  assert.equal(f.calls[1][1].sha, 'tested');
  assert.equal(f.calls[2][1].ref, 'main');
});
test('reuses existing PR', async () => {
  const f = fixture({ existing: true }); await integrate(f.args);
  assert.deepEqual(f.calls.map(c => c[0]), ['merge', 'dispatch']);
});
test('syncs a behind branch and retests instead of merging', async () => {
  const f = fixture({ comparison: { behind_by: 1 } }); await integrate(f.args);
  assert.deepEqual(f.calls.map(c => c[0]), ['create', 'update', 'dispatch']);
  assert.equal(f.calls[1][1].expected_head_sha, 'tested');
  assert.equal(f.calls[2][1].ref, 'codex/test');
});
for (const [name, options] of [
  ['main', { context: { ref: 'refs/heads/main' } }],
  ['external branch', { context: { ref: 'refs/heads/feature/test' } }],
  ['PR event', { context: { eventName: 'pull_request' } }],
  ['stale build', { head: 'new' }],
  ['no changes', { comparison: { ahead_by: 0 } }]
]) test(`ignores ${name}`, async () => {
  const f = fixture(options); await integrate(f.args); assert.deepEqual(f.calls, []);
});
for (const [name, options] of [
  ['draft', { pr: { draft: true } }],
  ['changed PR head', { pr: { head: { sha: 'new' } } }],
  ['unresolved comment', { unresolved: true }],
  ['changes requested', { reviews: [{ user: { login: 'reviewer' }, state: 'CHANGES_REQUESTED' }] }],
  ['conflicts', { pr: { mergeable: false } }],
  ['update conflict', { comparison: { behind_by: 1 }, conflict: true }],
  ['concurrent push', { moving: true }],
  ['merge refused', { refused: true }]
]) test(`stops publication on ${name}`, async () => {
  const f = fixture(options); await assert.rejects(integrate(f.args));
  assert.equal(f.calls.some(c => c[0] === 'dispatch'), false);
});
test('recovers publication dispatch after an earlier merge', async () => {
  const f = fixture({ comparison: { ahead_by: 0 }, closed: [{ merged_at: 'today', head: { sha: 'tested' } }] });
  await integrate(f.args);
  assert.deepEqual(f.calls.map(c => c[0]), ['dispatch']);
  assert.equal(f.calls[0][1].ref, 'main');
});
