const { test } = require('node:test');
const assert = require('node:assert/strict');
const modulePromise = import('../../../scripts/check-wechat-identity-readiness.mjs');
const complete = () => ({ MYSQL_HOST: 'db.internal', MYSQL_DATABASE: 'identity',
  MYSQL_USER: 'user', MYSQL_PASSWORD: 'secret-password', JWT_SECRET: 'a'.repeat(32),
  WECHAT_APP_ID: 'wx1234567890abcdef', WECHAT_APP_SECRET: 'secret-app' });

test('identity readiness reports missing configuration without accepting live identities', async () => {
  const { inspectIdentityReadiness } = await modulePromise;
  const report = inspectIdentityReadiness({});
  assert.equal(report.configurationComplete, false);
  assert.equal(report.formalIdentityAccepted, false);
  assert.ok(report.gaps.includes('CONFIG_WECHAT_APP_SECRET_MISSING'));
});
test('complete configuration does not prove SMS, database or employee binding', async () => {
  const { inspectIdentityReadiness } = await modulePromise;
  const report = inspectIdentityReadiness(complete());
  assert.equal(report.configurationComplete, true);
  assert.equal(report.formalIdentityAccepted, false);
  assert.ok(report.gaps.includes('REAL_STAFF_WECHAT_BINDING_NOT_VERIFIED'));
  assert.ok(report.gaps.includes('IDENTITY_DATABASE_CONNECTIVITY_NOT_VERIFIED'));
});
test('cloud path permits absent exchange secret but requires network trust verification', async () => {
  const { inspectIdentityReadiness } = await modulePromise;
  const env = complete(); delete env.WECHAT_APP_SECRET;
  const report = inspectIdentityReadiness({ ...env, WECHAT_CLOUD_RUN_ENABLED: 'true' });
  assert.equal(report.configurationComplete, true);
  assert.ok(report.gaps.includes('CLOUD_PUBLIC_ACCESS_DISABLED_NOT_VERIFIED'));
  assert.equal(inspectIdentityReadiness({ ...env, WECHAT_CLOUD_RUN_ENABLED: 'false' }).configurationComplete, false);
});
test('placeholder secrets, malformed AppID and invalid cloud flag cannot pass', async () => {
  const { inspectIdentityReadiness } = await modulePromise;
  for (const [key, value] of [['JWT_SECRET', 'changeme'], ['MYSQL_PASSWORD', 'unconfigured'],
    ['WECHAT_APP_ID', 'invalid-app'], ['WECHAT_CLOUD_RUN_ENABLED', 'yes']]) {
    assert.equal(inspectIdentityReadiness({ ...complete(), [key]: value }).configurationComplete, false);
  }
});
test('JWT length follows backend UTF-8 byte constraint', async () => {
  const { inspectIdentityReadiness } = await modulePromise;
  assert.equal(inspectIdentityReadiness({ ...complete(), JWT_SECRET: 'a'.repeat(31) }).configurationComplete, false);
  assert.equal(inspectIdentityReadiness({ ...complete(), JWT_SECRET: '密'.repeat(11) }).configurationComplete, true);
});
test('setting SMS_PROVIDER cannot activate absent sender implementations', async () => {
  const { inspectIdentityReadiness } = await modulePromise;
  const report = inspectIdentityReadiness({ ...complete(), SMS_PROVIDER: 'tencent' });
  assert.equal(report.sms.providerSettingActivatesSender, false);
  assert.ok(report.gaps.includes('SMS_ADAPTER_NOT_IMPLEMENTED'));
});
test('report omits secret values and unrelated identity data', async () => {
  const { inspectIdentityReadiness } = await modulePromise;
  const env = { ...complete(), PHONE: '13800138000', TOKEN: 'private-token' };
  const output = JSON.stringify(inspectIdentityReadiness(env));
  for (const value of Object.values(env)) assert.equal(output.includes(value), false);
});
test('explicit dotenv file does not inherit otherwise complete caller settings', async () => {
  const { runReadinessCli } = await modulePromise;
  const result = runReadinessCli(['--env-file', 'local.env'], complete(), () => 'WECHAT_CLOUD_RUN_ENABLED=false\n');
  assert.equal(result.exitCode, 2);
  assert.equal(JSON.parse(result.output).configurationComplete, false);
});
test('read failures and invalid arguments never echo paths or errors', async () => {
  const { runReadinessCli } = await modulePromise;
  const result = runReadinessCli(['--env-file', 'secret-path'], {}, () => { throw new Error('secret-value'); });
  assert.equal(result.exitCode, 1);
  assert.equal(result.output, '{"error":"CONFIG_READ_FAILED"}\n');
  assert.equal(runReadinessCli(['--secret-value'], {}).output, '{"error":"INVALID_ARGUMENTS"}\n');
});
