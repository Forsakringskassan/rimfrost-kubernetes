Run the bash command below exactly as written and display the output as a markdown table. Do not paraphrase or reformat — print the raw table from stdout.

```bash
python3 << 'PYEOF'
import subprocess, json, re, sys, datetime

def parse_version(v):
    try:
        return tuple(int(x) for x in re.split(r'[.\-]', v) if x.isdigit())
    except Exception:
        return (0,)

def gh_api(path, extra_args=None):
    cmd = ['gh', 'api', path]
    if extra_args:
        cmd.extend(extra_args)
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode == 0 and r.stdout.strip():
        return r.stdout.strip()
    return None

# Fallback repo names when container image name != GitHub repo name
REPO_FALLBACKS = {
    'folkbokford': 'rimfrost-service-folkbokforing',
    'arbetsgivare': 'rimfrost-service-arbetsgivare',
    'rimfrost-operativt-uppgiftslager': 'rimfrost-service-oul',
    'rimfrost-vard-av-husdjur': 'rimfrost-process-vah',
    'rimfrost-vard-av-boskap': 'rimfrost-process-vab',
}

with open("helm-chart/values.yaml") as f:
    content = f.read()

entries = []
for block in re.split(r'\n  - name: ', content)[1:]:
    name_m = re.match(r'(\S+)', block)
    repo_m  = re.search(r'repository:\s*(\S+)', block)
    tag_m   = re.search(r'tag:\s*(\S+)', block)
    if name_m and repo_m and tag_m:
        entries.append((name_m.group(1), repo_m.group(1), tag_m.group(1)))

col_name    = max(max(len(e[0]) for e in entries), 4)
col_tag     = max(max(len(e[2]) for e in entries), 11)
col_rel_tag = len('Latest Release')
col_rel_dt  = len('Release Date')
col_commits = len('Commits Since')

# Pre-compute repo names to size the column
def get_repo_name(pkg):
    return REPO_FALLBACKS.get(pkg, pkg)

repo_names = [get_repo_name(re.search(r'repository:\s*(\S+)', b).group(1).rstrip('/').split('/')[-1])
              for b in re.split(r'\n  - name: ', content)[1:]
              if re.search(r'repository:\s*(\S+)', b)]
col_repo = max(max(len(r) for r in repo_names), len('GitHub Repo'))

header = (f"| {'Name':<{col_name}} | {'GitHub Repo':<{col_repo}} | {'Current Tag':<{col_tag}} "
          f"| {'Latest Release':<{col_rel_tag}} | {'Release Date':<{col_rel_dt}} "
          f"| {'Commits Since':<{col_commits}} | Available Tags (latest first) |")
sep = (f"|{'-'*(col_name+2)}|{'-'*(col_repo+2)}|{'-'*(col_tag+2)}"
       f"|{'-'*(col_rel_tag+2)}|{'-'*(col_rel_dt+2)}"
       f"|{'-'*(col_commits+2)}|-------------------------------|")
timestamp = datetime.datetime.now().strftime('%Y-%m-%d %H:%M')
lines = [f'_Generated: {timestamp}_\n', header, sep]

print(header)
print(sep)

for name, repo, current_tag in entries:
    parts = repo.rstrip('/').split('/')
    org   = parts[-2]
    pkg   = parts[-1]

    # Available tags from container registry
    result = subprocess.run(
        ['gh', 'api', f'/orgs/{org}/packages/container/{pkg}/versions',
         '--paginate', '--jq', '[.[].metadata.container.tags[]] | map(select(. != "")) | unique'],
        capture_output=True, text=True
    )
    if result.returncode == 0 and result.stdout.strip():
        all_tags = []
        for line in result.stdout.strip().splitlines():
            line = line.strip()
            if line.startswith('['):
                try:
                    all_tags.extend(json.loads(line))
                except Exception:
                    pass
        all_tags = [t for t in all_tags if re.match(r'^\d+\.\d+', t)]
        all_tags = sorted(set(all_tags), key=parse_version, reverse=True)
        shown = all_tags[:5]
        tags_str = ', '.join(shown) + (', ...' if len(all_tags) > 5 else '') if shown else '(none)'
    elif result.returncode != 0:
        tags_str = f'error: {result.stderr.strip()[:60]}'
    else:
        tags_str = '(none)'

    # Resolve GitHub repo name (may differ from container image name)
    repo_name = REPO_FALLBACKS.get(pkg, pkg)

    # Latest GitHub release
    rel_raw = gh_api(f'repos/{org}/{repo_name}/releases/latest')
    rel_tag = ''
    rel_date = ''
    commits_since = ''
    if rel_raw:
        try:
            rel = json.loads(rel_raw)
            rel_tag  = rel.get('tag_name', '')
            published = rel.get('published_at', '')
            rel_date = published[:10] if published else ''
            # Count commits on main since release
            commits_raw = gh_api(
                f'repos/{org}/{repo_name}/commits?sha=main&since={published}',
                ['--paginate', '--jq', 'length']
            )
            if commits_raw:
                total = sum(int(x) for x in commits_raw.strip().splitlines() if x.strip().isdigit())
                commits_since = str(total)
            else:
                commits_since = '0'
        except Exception:
            pass

    row = (f"| {name:<{col_name}} | {repo_name:<{col_repo}} | {current_tag:<{col_tag}} "
           f"| {rel_tag:<{col_rel_tag}} | {rel_date:<{col_rel_dt}} "
           f"| {commits_since:<{col_commits}} | {tags_str} |")
    lines.append(row)
    print(row)

with open("image-tags-overview.md", "w") as f:
    f.write('\n'.join(lines) + '\n')
PYEOF
```
