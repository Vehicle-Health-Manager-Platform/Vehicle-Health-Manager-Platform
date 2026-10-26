"""Every contract operation is either fully evidenced or has exactly one explicit verdict."""
import argparse
import json
from collections import Counter, OrderedDict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / 'docs/api/openapi.json'
COVERAGE = ROOT / 'docs/testing/api-coverage.json'
VERDICTS = ROOT / 'docs/api/CONTRACT_VERDICTS.json'
OUTPUT = ROOT / 'docs/api/CONTRACT_RECONCILIATION.md'
KINDS = OrderedDict([
    ('superseded', '已被具名实现替代'),
    ('internal-only', '由服务内部事务完成'),
    ('implemented-local', '本地软件已实现，完整历史HTTP证据待补'),
    ('blocked-external', '依赖未接入的外部上游'),
    ('not-implemented', '尚未实现(需先立项)'),
    ('no-success-path', '实现存在但无成功路径'),
    ('dev-only', '仅本地演示或开发端点'),
])
TOTAL_CONTRACT = 353
MIN_EVIDENCED = 317


def require(condition, message):
    if not condition:
        raise ValueError(message)


def operations(contract):
    rows = {}
    for path, item in contract['paths'].items():
        for method, operation in item.items():
            rows[(method.upper(), path)] = {'operation_id': operation.get('operationId'),
                                            'status': operation.get('x-implementation-status'),
                                            'summary': operation.get('summary')}
    return rows


def evidenced(coverage):
    """Contract paths may have generated operation ids, so always key by method and path."""
    return {(operation['method'].upper(), operation['path']) for operation in coverage['operations']
            if operation['historical_kinds'] and not operation['missing_historical_kinds']}


def subjects(operations_, complete):
    """Not (implemented and fully evidenced): each of these needs a verdict."""
    return {key for key, operation in operations_.items()
            if operation['status'] is None or key not in complete}


def load_verdicts():
    data = json.loads(VERDICTS.read_text(encoding='utf-8'))
    require(data.get('version') == 1, 'Verdict file must declare version 1')
    require(bool((data.get('scope') or '').strip()), 'Verdict file must describe its scope')
    rows = data.get('verdicts')
    require(isinstance(rows, list) and bool(rows), 'Verdicts must be a non-empty list')
    return rows


def validate(operations_, complete, rows):
    seen = {}
    for row in rows:
        label = row.get('operation')
        require(isinstance(label, str) and label.count(' ') == 1, 'Verdict operation must be "METHOD /path"')
        method, path = label.split(' ')
        require(method == method.upper(), 'Verdict method must be uppercase: ' + label)
        key = (method, path)
        require(key not in seen, 'Duplicate verdict for ' + label)
        require(row.get('verdict') in KINDS, 'Unknown verdict for ' + label)
        reason = row.get('reason')
        require(isinstance(reason, str) and len(reason.strip()) >= 10, 'Verdict reason too short for ' + label)
        require(key in operations_, 'Verdict for an operation outside the contract: ' + label)
        seen[key] = row
        if row['verdict'] == 'superseded':
            replacement = row.get('replacement')
            require(isinstance(replacement, str) and replacement.count(' ') == 1,
                    'Superseded verdict needs a "METHOD /path" replacement: ' + label)
            rmethod, rpath = replacement.split(' ')
            require(rmethod == rmethod.upper(), 'Replacement method must be uppercase: ' + label)
            require((rmethod, rpath) != key, 'Replacement must differ from the verdict operation: ' + label)
            require((rmethod, rpath) in operations_, 'Replacement is outside the contract: ' + label)
            require(operations_[(rmethod, rpath)]['status'],
                    'Replacement is itself unclassified: ' + label + ' -> ' + replacement)
        else:
            require('replacement' not in row, 'Only superseded verdicts may name a replacement: ' + label)
    expected = subjects(operations_, complete)
    missing = sorted('%s %s' % key for key in expected - set(seen))
    require(not missing, 'Unresolved contract operations: ' + ', '.join(missing))
    extra = sorted('%s %s' % key for key in set(seen) - expected)
    require(not extra, 'Verdicts on already evidenced operations hide real evidence: ' + ', '.join(extra))
    return seen


def reconcile():
    contract = json.loads(CONTRACT.read_text(encoding='utf-8'))
    coverage = json.loads(COVERAGE.read_text(encoding='utf-8'))
    operations_ = operations(contract)
    complete = evidenced(coverage)
    rows = validate(operations_, complete, load_verdicts())
    require(len(operations_) == TOTAL_CONTRACT, 'Contract operation count changed without review')
    require(len(complete) >= MIN_EVIDENCED, 'Evidenced operation count regressed below the recorded floor')
    require(len(complete) + len(rows) == len(operations_), 'Evidenced plus verdicts must cover the contract')
    kinds = Counter(row['verdict'] for row in rows.values())
    require(set(kinds) <= set(KINDS), 'Unknown verdict kind')
    return contract, coverage, operations_, complete, rows, kinds


def render(contract, coverage, operations_, complete, rows, kinds):
    lines = ['# 契约逐项结论', '',
             '本文件由 `python scripts/reconcile_contract.py` 生成，请勿手工编辑；结论数据在 '
             '[CONTRACT_VERDICTS.json](CONTRACT_VERDICTS.json)。', '',
             '- 契约操作：**%d** 项；具备四类历史证据：**%d** 项；有明确结论但**不计证据**：**%d** 项。'
             % (len(operations_), len(complete), len(rows)),
             '- 未解释的操作：**0** 项（脚本 `--check` 会在出现未结案条目时失败）。', '',
             '结论只回答"这一项为什么没有四类证据"，**不等于**该功能已验收、已上线或已可用。'
             '真实执行证据见 [API_COVERAGE.md](../testing/API_COVERAGE.md)，全 API 验收仍为 '
             '`%s`。' % str(coverage.get('full_api_acceptance_passed', False)).lower(), '',
             '## 结论分布', '', '| 结论 | 含义 | 项数 |', '| --- | --- | --- |']
    for kind, label in KINDS.items():
        lines.append('| `%s` | %s | %d |' % (kind, label, kinds.get(kind, 0)))
    lines += ['', '## 明细', '']
    order = sorted(rows.items(), key=lambda item: (list(KINDS).index(item[1]['verdict']), item[0][1], item[0][0]))
    for kind, label in KINDS.items():
        group = [(key, row) for key, row in order if row['verdict'] == kind]
        if not group:
            continue
        lines += ['### %s（%d 项）' % (label, len(group)), '']
        if kind == 'superseded':
            lines += ['| 操作 | 替代实现 | 说明 |', '| --- | --- | --- |']
            for key, row in group:
                lines.append('| `%s %s` | `%s` | %s |' % (key[0], key[1], row['replacement'], row['reason']))
        else:
            lines += ['| 操作 | 当前实现标记 | 说明 |', '| --- | --- | --- |']
            for key, row in group:
                lines.append('| `%s %s` | `%s` | %s |' % (key[0], key[1],
                                                           operations_[key]['status'] or '未标记', row['reason']))
        lines.append('')
    lines += ['## 复核', '', '```bash', '# 重新生成并校验(CI 使用 --check)',
              'python scripts/reconcile_contract.py', 'python scripts/reconcile_contract.py --check',
              'python -m unittest discover -s scripts -p test_reconcile_contract.py -v', '```', '']
    return '\n'.join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Only compare the generated file with the committed one.')
    args = parser.parse_args(argv)
    contract, coverage, operations_, complete, rows, kinds = reconcile()
    text = render(contract, coverage, operations_, complete, rows, kinds)
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text(encoding='utf-8') != text:
            raise SystemExit('Contract reconciliation is stale: run python scripts/reconcile_contract.py')
    else:
        OUTPUT.write_text(text, encoding='utf-8')
    print('Contract reconciliation PASS; %d operations = %d evidenced + %d verdicts; %s'
          % (len(operations_), len(complete), len(rows),
             ', '.join('%s=%d' % (kind, count) for kind, count in kinds.items())))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
