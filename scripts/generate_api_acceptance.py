"""Generate guarded Postman assets and an explicit, unexecuted acceptance inventory."""
import json
import hashlib
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'docs/testing/postman'
METHODS = {'get', 'post', 'put', 'patch', 'delete', 'head', 'options'}
SCHEMA = 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json'


def operations(spec):
    result, ids = [], set()
    for path, item in sorted(spec['paths'].items()):
        for method, operation in sorted(item.items()):
            if method not in METHODS:
                continue
            name = operation.get('operationId') or ('generated_' + method + '_' + hashlib.sha256(path.encode()).hexdigest()[:16])
            if name in ids or not re.fullmatch(r'[A-Za-z0-9_]+', name):
                raise ValueError('Missing, duplicate or unsafe operationId')
            ids.add(name)
            security = operation.get('security', spec.get('security', []))
            # An empty security alternative explicitly allows anonymous access.
            protected = bool(security) and all(bool(alternative) for alternative in security)
            result.append((method.upper(), path, dict(operation, operationId=name), protected))
    return result


def collection(name, items):
    return {'info': {'name': name, 'schema': SCHEMA},
            'variable': [{'key': 'base_url', 'value': 'http://127.0.0.1:1'},
                         {'key': 'enable_prepared_requests', 'value': 'false'},
                         {'key': 'access_token', 'value': ''}], 'item': items}


def generate(spec):
    anonymous, prepared, inventory = [], [], []
    for method, path, op, protected in operations(spec):
        name = op['operationId']
        # Anonymous probes test authentication before binding; ids are synthetic placeholders.
        concrete = re.sub(r'\{[^}]+\}', '1', path)
        request = {'method': method, 'header': [], 'auth': {'type': 'noauth'},
                   'url': '{{base_url}}' + concrete,
                   'description': op.get('summary', '')}
        if method in {'POST', 'PUT', 'PATCH', 'DELETE'}:
            request['header'] = [{'key': 'Content-Type', 'value': 'application/json'}]
            request['body'] = {'mode': 'raw', 'raw': '{}'}
        if protected:
            anonymous.append({'name': name, 'request': request,
                'event': [{'listen': 'test', 'script': {'type': 'text/javascript', 'exec': [
                    'pm.test("anonymous is rejected", () => pm.response.to.have.status(401));',
                    'pm.test("safe JSON envelope", () => pm.expect(pm.response.json().code).to.eql(40100));',
                    'pm.test("private response is not cached", () => pm.expect(pm.response.headers.get("Cache-Control")).to.include("no-store"));'
                ]}}]})
        catalog_request = {'method': method, 'url': '{{base_url}}' + re.sub(r'\{([^}]+)\}', lambda m: '{{' + name + '_' + m[1] + '}}', path),
                           'header': [], 'description': op.get('summary', '') + '\nRoles: ' + str(op.get('x-roles', 'UNSPECIFIED'))}
        if protected:
            catalog_request['auth'] = {'type': 'bearer', 'bearer': [{'key': 'token', 'type': 'string', 'value': '{{access_token}}'}]}
        else:
            catalog_request['auth'] = {'type': 'noauth'}
        if op.get('requestBody'):
            # Do not invent valid business payloads or private file fixture paths.
            catalog_request['body'] = {'mode': 'raw', 'raw': '{{body_' + name + '}}'}
        required = ['status_' + name] + [name + '_' + p for p in re.findall(r'\{([^}]+)\}', path)]
        query = []
        for parameter in op.get('parameters', []):
            key, place = parameter.get('name'), parameter.get('in')
            variable = name + '_' + str(key).replace('-', '_')
            if place == 'query':
                query.append(key + '={{' + variable + '}}')
            elif place == 'header':
                catalog_request['header'].append({'key': key, 'value': '{{' + variable + '}}'})
            if place in {'query', 'header'}:
                # Optional parameters are explicit too: blank or remove them when not used.
                required.append(variable)
        if query:
            catalog_request['url'] += '?' + '&'.join(query)
        content = op.get('requestBody', {}).get('content', {})
        if 'application/json' in content:
            catalog_request['header'].append({'key': 'Content-Type', 'value': 'application/json'})
        elif 'multipart/form-data' in content:
            catalog_request['body'] = {'mode': 'formdata', 'formdata': [{'key': 'file', 'type': 'file', 'src': '{{file_' + name + '}}'}]}
        if protected:
            required.append('access_token')
        if op.get('requestBody'):
            required.append(('file_' if 'multipart/form-data' in content else 'body_') + name)
        prepared.append({'name': name, 'request': catalog_request, 'event': [
            {'listen': 'prerequest', 'script': {'type': 'text/javascript', 'exec': [
                'if (pm.variables.get("enable_prepared_requests") !== "true") { pm.execution.skipRequest(); }',
                'else {',
                '  const missing = ' + json.dumps(required) + '.filter(k => !pm.variables.get(k));',
                '  if (missing.length) { pm.execution.skipRequest(); throw new Error("Configure fixture variables before sending: " + missing.join(",")); }',
                '}'
            ]}},
            {'listen': 'test', 'script': {'type': 'text/javascript', 'exec': [
                'pm.test("configured scenario status", () => pm.response.to.have.status(Number(pm.variables.get(' + json.dumps('status_' + name) + '))));'
            ]}}
        ]})
        inventory.append({'operation_id': name, 'method': method, 'path': path,
                          'roles': op.get('x-roles'), 'implementation_status': op.get('x-implementation-status', 'unspecified'),
                          'content_types': sorted(op.get('requestBody', {}).get('content', {})),
                          'required_query': [p['name'] for p in op.get('parameters', []) if p.get('in') == 'query' and p.get('required')],
                          'anonymous_expected': 401 if protected else None,
                          'cases': {'normal': 'NOT_RUN', 'boundary': 'NOT_RUN', 'error': 'NOT_RUN',
                                    'unauthorized': 'NOT_RUN' if protected else 'PUBLIC_REQUIRES_SEPARATE_REVIEW'}})
    return {'anonymous.postman_collection.json': collection('Autocare anonymous authentication checks', anonymous),
            'prepared.postman_collection.json': collection('Autocare prepared contracts - disabled by default', prepared),
            'acceptance_inventory.json': {'version': 1, 'operations': len(inventory),
                'anonymous_probes': len(anonymous), 'full_api_acceptance_passed': False,
                'note': 'Generated inventory, not test evidence. Anonymous 401 does not prove controller implementation or role/ownership validation.',
                'items': inventory}}


def main():
    spec = json.loads((ROOT / 'docs/api/openapi.json').read_text(encoding='utf-8'))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, document in generate(spec).items():
        (OUTPUT / name).write_text(json.dumps(document, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('Postman assets generated; no acceptance cases marked passed.')


if __name__ == '__main__':
    main()
