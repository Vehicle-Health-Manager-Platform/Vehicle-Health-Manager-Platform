// Explicit fixture writes only to the fixed isolated Docker project.
const { spawnSync } = require('node:child_process')
if (!process.argv.includes('--allow-local-test-writes')) {
  console.error('Requires --allow-local-test-writes; isolated synthetic projects only.'); process.exit(2)
}
const container = 'vehicle-auth-local-mysql-1'
function docker(args, input) {
  const result = spawnSync('docker', args, { input, encoding: 'utf8', windowsHide: true })
  if (result.status !== 0) throw new Error('Local database operation failed')
  return result.stdout.trim()
}
const sql = query => docker(['exec','-i',container,'sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE" --batch --skip-column-names'],query)
try {
  if (docker(['inspect',container,'--format','{{index .Config.Labels "com.docker.compose.project"}}']) !== 'vehicle-auth-local')
    throw new Error('Wrong Docker project')
  const rows = Array.from({ length:26 }, (_,i) => {
    const n=i+1, id=9101100+n, name=`本地合成测试服务${String(n).padStart(2,'0')}`
    return { id, name, category:n<=22?1:2, status:n===25?0:1, deleted:n===26?1:0 }
  })
  // Validate both reserved IDs and unique names before writing. Existing fields
  // must match exactly; never overwrite a changed or unrelated project.
  for(const row of rows) {
    const predicate=`id=${row.id} AND project_name='${row.name}' AND category=${row.category} AND service_content='本地合成服务内容，仅供联调' AND quality_standard IS NULL AND base_price_low=0.10 AND base_price_high=199.99 AND status=${row.status} AND is_deleted=${row.deleted}`
    if(sql(`SELECT COUNT(*) FROM standard_project WHERE (id=${row.id} OR project_name='${row.name}') AND NOT COALESCE((${predicate}),0);`) !== '0')
      throw new Error('Synthetic fixture conflict')
  }
  sql('START TRANSACTION;\n'+rows.map(row=>`INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high,status,is_deleted) SELECT ${row.id},'${row.name}',${row.category},'本地合成服务内容，仅供联调',0.10,199.99,${row.status},${row.deleted} WHERE NOT EXISTS(SELECT 1 FROM standard_project WHERE id=${row.id});`).join('\n')+'\nCOMMIT;')
  console.log('Prepared 26 synthetic service fixtures: 24 active, one disabled, one deleted; credentials withheld.')
} catch { console.error('Fixture setup refused or failed; check fixed isolated project and ID conflicts.'); process.exitCode=1 }
