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

export function runReadinessCli(args, inheritedEnv, read = readFileSync) {
  if (args.length === 1 && args[0] === '--help') return {
    exitCode: 0, output: 'Usage: node scripts/check-wechat-identity-readiness.mjs [--env-file FILE]\n'
      + 'Read only. No values printed. Exit 2 means formal identity still needs acceptance.\n',
  };
  if (args.length !== 0 && !(args.length === 2 && args[0] === '--env-file')) {
    return { exitCode: 1, output: '{"error":"INVALID_ARGUMENTS"}\n' };
  }
  try {
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
