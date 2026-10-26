import { readFileSync } from 'node:fs';
import { parseEnv } from 'node:util';
import { pathToFileURL } from 'node:url';

// Only statuses and fixed field names may leave this module; never echo configuration.
const placeholders = /^(unconfigured|changeme|change[-_ ]?me|replace[-_ ].*|your[-_ ].*|<.*>)$/i;
const state = value => typeof value !== 'string' || !value.trim()
  ? 'missing' : placeholders.test(value.trim()) ? 'placeholder' : 'configured';

export function inspectIdentityReadiness(env) {
  const cloudValue = env.WECHAT_CLOUD_RUN_ENABLED;
  const cloud = typeof cloudValue === 'string' && cloudValue.toLowerCase() === 'true';
  const fields = ['MYSQL_HOST', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD',
    'JWT_SECRET', 'WECHAT_APP_ID', 'WECHAT_APP_SECRET'].map(key => {
    let status = state(env[key]);
    if (status === 'configured' && key === 'JWT_SECRET' &&
        Buffer.byteLength(env[key], 'utf8') < 32) status = 'invalid';
    if (status === 'configured' && key === 'WECHAT_APP_ID' &&
        !/^wx[0-9a-f]{16}$/i.test(env[key])) status = 'invalid';
    return { key, status, required: key !== 'WECHAT_APP_SECRET' || !cloud };
  });
  fields.push({ key: 'WECHAT_CLOUD_RUN_ENABLED', required: true,
    status: cloudValue === undefined || cloudValue === '' || /^(true|false)$/i.test(cloudValue)
      ? 'configured' : 'invalid' });
  const gaps = fields.filter(field => field.required && field.status !== 'configured')
    .map(field => `CONFIG_${field.key}_${field.status.toUpperCase()}`);
  if (cloud) gaps.push('CLOUD_PUBLIC_ACCESS_DISABLED_NOT_VERIFIED');
  gaps.push('SMS_SERVICE_NOT_OPENED', 'SMS_ADAPTER_NOT_IMPLEMENTED',
    'SMS_SIGNATURE_TEMPLATE_NOT_VERIFIED', 'AUTHORIZED_SMS_LOGIN_NOT_VERIFIED',
    'IDENTITY_DATABASE_CONNECTIVITY_NOT_VERIFIED', 'REAL_STAFF_WECHAT_BINDING_NOT_VERIFIED');
  return {
    schemaVersion: 1,
    scope: 'R0.2a-offline-configuration',
    loginPath: cloud ? 'cloud-callContainer' : 'direct-code2session',
    configurationComplete: fields.every(field => !field.required || field.status === 'configured'),
    formalIdentityAccepted: false,
    fields, gaps,
    sms: { merchant: 'adapter-not-implemented', operator: 'adapter-not-implemented',
      providerSettingActivatesSender: false },
  };
}

// B1a compares three explicit local inputs. A declaration is never proof of
// deployment, gateway authenticity, account ownership or a real login.
export function inspectCloudDeploymentReadiness(backend, miniapp, deployment, manifest) {
  const identity = inspectIdentityReadiness(backend);
  const checks = [];
  const add = (key, passed) => checks.push({ key, status: passed ? 'configured' : 'blocked' });
  const identifier = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$/.test(value)
    && state(value) === 'configured';
  const keys = ['schema_version', 'target', 'environment_id', 'service', 'region', 'public_access', 'identity_gateway'];
  const shape = deployment !== null && typeof deployment === 'object' && !Array.isArray(deployment)
    && Object.keys(deployment).length === keys.length && keys.every(k => Object.hasOwn(deployment, k));
  const declared = shape ? deployment : {};
  add('DEPLOYMENT_DECLARATION', shape && declared.schema_version === 1 && declared.target === 'wechat-cloudrun');
  add('CLOUD_BACKEND_ENABLED', backend.WECHAT_CLOUD_RUN_ENABLED === 'true');
  for (const field of identity.fields.filter(f => f.required && f.key !== 'WECHAT_CLOUD_RUN_ENABLED')) {
    add('BACKEND_' + field.key, field.status === 'configured');
  }
  const host = typeof backend.MYSQL_HOST === 'string' ? backend.MYSQL_HOST.trim().toLowerCase() : '';
  add('EXTERNAL_MYSQL_HOST', Boolean(host) && !['localhost', 'mysql', 'db', '0.0.0.0', '::1', '[::1]'].includes(host)
    && !/^127\./.test(host) && !/[\s/@:]/.test(host));
  add('MINIAPP_APP_ID_MATCH', /^wx[0-9a-f]{16}$/i.test(backend.WECHAT_APP_ID || '')
    && backend.WECHAT_APP_ID === manifest?.['mp-weixin']?.appid);
  add('ENVIRONMENT_ID_MATCH', identifier(miniapp.VITE_WECHAT_CLOUD_ENV_ID)
    && miniapp.VITE_WECHAT_CLOUD_ENV_ID === declared.environment_id);
  add('SERVICE_NAME_MATCH', identifier(miniapp.VITE_WECHAT_CLOUD_SERVICE)
    && miniapp.VITE_WECHAT_CLOUD_SERVICE === declared.service);
  add('REGION_DECLARED', identifier(declared.region));
  add('PRIVATE_GATEWAY_DECLARED', declared.public_access === 'disabled'
    && declared.identity_gateway === 'wechat-managed-headers');
  // This project's current phone client still uses stable_token + AppSecret.
  // Cloud login working without a secret does not imply phone binding works.
  const secretReady = identity.fields.find(f => f.key === 'WECHAT_APP_SECRET').status === 'configured';
  const ready = checks.every(c => c.status === 'configured');
  return {
    schemaVersion: 1,
    scope: 'B1a-offline-cloud-deployment-configuration',
    target: 'WeChat mini-program and existing Java/MySQL backend',
    configurationReady: ready,
    cloudDeployed: false,
    formalIdentityAccepted: false,
    trustEvidence: 'local-declaration-only',
    checks,
    capabilities: {
      cloudLogin: ready ? 'configuration-ready-runtime-unverified' : 'configuration-blocked',
      phoneBinding: ready && secretReady ? 'configuration-ready-permission-unverified' : 'configuration-blocked',
      technicianBinding: 'existing-code-real-account-unverified',
      merchantSmsLogin: 'deferred-no-bypass', operatorSmsLogin: 'deferred-no-bypass',
      fileUpload: 'cloud-storage-adapter-required',
    },
    pending: ['CLOUD_ENVIRONMENT_OWNERSHIP', 'CALL_CONTAINER_AND_IDENTITY_HEADER_COMPATIBILITY',
      'PUBLIC_DIRECT_ACCESS_DISABLED', 'MYSQL_CONNECTIVITY_AND_MIGRATIONS', 'REAL_OWNER_AND_TECHNICIAN_LOGIN',
      'WECHAT_PHONE_PERMISSION_AND_REAL_BINDING', 'PRIVATE_STORAGE_AND_SCAN_INTEGRATION', 'IOS_ANDROID_ACCEPTANCE'],
  };
}

export function runReadinessCli(args, inheritedEnv, read = readFileSync) {
  if (args.length === 1 && args[0] === '--help') return {
    exitCode: 0, output: 'Usage: node scripts/check-wechat-identity-readiness.mjs [--env-file FILE]\n'
      + 'Cloud: --cloud-deployment --env-file FILE --miniapp-env-file FILE --deployment-file FILE\n'
      + 'Read only. No values printed. Exit 2 means formal identity still needs acceptance.\n',
  };
  const cloudMode = args.length === 7 && args[0] === '--cloud-deployment' && args[1] === '--env-file'
    && args[3] === '--miniapp-env-file' && args[5] === '--deployment-file';
  if (!cloudMode && args.length !== 0 && !(args.length === 2 && args[0] === '--env-file')) {
    return { exitCode: 1, output: '{"error":"INVALID_ARGUMENTS"}\n' };
  }
  try {
    if (cloudMode) {
      const report = inspectCloudDeploymentReadiness(parseEnv(read(args[2], 'utf8')),
        parseEnv(read(args[4], 'utf8')), JSON.parse(read(args[6], 'utf8')),
        JSON.parse(read(new URL('../apps/miniapp/src/manifest.json', import.meta.url), 'utf8')));
      return { exitCode: 2, output: JSON.stringify(report, null, 2) + '\n' };
    }
    // An explicit file is isolated from the caller environment to avoid a false pass.
    const env = args.length ? parseEnv(read(args[1], 'utf8')) : inheritedEnv;
    return { exitCode: 2, output: JSON.stringify(inspectIdentityReadiness(env), null, 2) + '\n' };
  } catch {
    return { exitCode: 1, output: '{"error":"CONFIG_READ_FAILED"}\n' };
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const result = runReadinessCli(process.argv.slice(2), process.env);
  process.stdout.write(result.output);
  process.exitCode = result.exitCode;
}
