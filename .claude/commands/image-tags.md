Run the bash command below exactly as written and display the output as a markdown table. Do not paraphrase or reformat — print the raw table from stdout.

```bash
python3 << 'PYEOF'
import subprocess, json, re, sys

def parse_version(v):
    try:
        return tuple(int(x) for x in re.split(r'[.\-]', v) if x.isdigit())
    except Exception:
        return (0,)

with open("helm-chart/values.yaml") as f:
    content = f.read()

entries = []
for block in re.split(r'\n  - name: ', content)[1:]:
    name_m = re.match(r'(\S+)', block)
    repo_m  = re.search(r'repository:\s*(\S+)', block)
    tag_m   = re.search(r'tag:\s*(\S+)', block)
    if name_m and repo_m and tag_m:
        entries.append((name_m.group(1), repo_m.group(1), tag_m.group(1)))

col_name = max(len(e[0]) for e in entries)
col_tag  = max(len(e[2]) for e in entries)
col_name = max(col_name, 4)
col_tag  = max(col_tag, 11)

header = f"| {'Name':<{col_name}} | {'Current Tag':<{col_tag}} | Available Tags (latest first) |"
sep    = f"|{'-'*(col_name+2)}|{'-'*(col_tag+2)}|-------------------------------|"
print(header)
print(sep)

for name, repo, current_tag in entries:
    parts = repo.rstrip('/').split('/')
    org   = parts[-2]
    pkg   = parts[-1]

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
        tags_str = ', '.join(all_tags) if all_tags else '(none)'
    elif result.returncode != 0:
        tags_str = f'error: {result.stderr.strip()[:60]}'
    else:
        tags_str = '(none)'

    print(f"| {name:<{col_name}} | {current_tag:<{col_tag}} | {tags_str} |")
PYEOF
```
