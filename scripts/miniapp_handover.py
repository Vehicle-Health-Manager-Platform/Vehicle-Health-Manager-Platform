"""Generate a source-bound WeChat page/template inventory; never certify runtime acceptance."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
OUTPUT='docs/delivery/MINIAPP_REFERENCE.md'
MANIFEST='docs/delivery/miniapp-handover.json'
TEMPLATES=('.env.example','apps/miniapp/.env.example','deploy/cloudrun/env.example')
GUIDES={role:'docs/user-guides/'+role.upper()+'.md' for role in ('owner','merchant','technician','operator')}
REMAINING='docs/delivery/REMAINING_ACCEPTANCE.md'


def read(root,name):return (root/name).read_text(encoding='utf-8-sig')
def digest(value):return hashlib.sha256(value.replace('\r\n','\n').encode()).hexdigest()


def validate_links(root,name,content):
    for target in re.findall(r'\]\(([^)]+)\)',content):
        target=target.strip('<>').split('#',1)[0]
        if not target or re.match(r'https?://',target):continue
        path=((root/name).parent/target).resolve()
        if not path.is_relative_to(root.resolve()) or (not path.is_file() and path!=(root/OUTPUT).resolve()):
            raise ValueError('Broken or outside-workspace documentation link in '+name+': '+target)


def build(root=ROOT):
    source='apps/miniapp/src/pages.json';document=json.loads(read(root,source))
    pages=[];seen=set();inputs={source:digest(read(root,source))}
    for row in document['pages']:
        path=row['path']
        if not re.fullmatch(r'pages/[a-z0-9/-]+',path) or path in seen:raise ValueError('Invalid or duplicate page: '+path)
        seen.add(path);file='apps/miniapp/src/'+path+'.vue'
        if not (root/file).is_file():raise ValueError('Registered page source missing: '+path)
        inputs[file]=digest(read(root,file))
        group=path.split('/')[1];role=group if group in GUIDES else 'owner'
        if group in ('workspace','check'):role='merchant'
        pages.append({'path':path,'title':row['style']['navigationBarTitleText'],'source':file,'guide':GUIDES[role]})
    for row in document['tabBar']['list']:
        if row['pagePath'] not in seen:raise ValueError('Tab page not registered')
    configs=[]
    for file in TEMPLATES:
        content=read(root,file);inputs[file]=digest(content);keys=[]
        for line in content.splitlines():
            if not line.strip() or line.lstrip().startswith('#'):continue
            match=re.fullmatch(r'([A-Z][A-Z0-9_]*)=(.*)',line.strip())
            if not match:raise ValueError('Unsupported environment template line: '+file)
            key,value=match.groups()
            if key in keys:raise ValueError('Duplicate environment variable: '+key)
            keys.append(key)
            # Only literal booleans are emitted. Secrets/URLs/IDs/default credentials never leave templates.
            configs.append({'source':file,'key':key,'template_boolean':value if value in ('true','false') else None})
    for file in (*GUIDES.values(),REMAINING):
        content=read(root,file);validate_links(root,file,content);inputs[file]=digest(content)
        for path in re.findall(r'(?<![\w/])pages/[a-z0-9/-]+',content):
            if path not in seen:raise ValueError('Guide references unregistered page: '+path)
    manifest={'version':1,'target':'WeChat mini-program and shared backend','runtime_acceptance_passed':False,
              'scope':'Registered page source and three tracked environment templates only; not all runtime variables, effective deployment, authorization or visual acceptance.',
              'counts':{'pages':len(pages),'template_files':len(TEMPLATES),'template_entries':len(configs),'guides':len(GUIDES)},
              'inputs':inputs,'pages':pages,'configuration':configs}
    lines=['# 当前微信小程序页面与模板索引','','由 `python scripts/miniapp_handover.py` 生成；请修改来源后重生成。',
           '',f'注册页面{len(pages)}项，三份环境模板共{len(configs)}条变量记录（同名跨模板分别计数）。',
           '这是源码存在性与文档链接核对，不是入口可达性、权限、视觉、真实身份或真机通过证明。按目录归入阅读指南，共用页可能服务多个角色，实际权限以服务端为准。',
           '', '[车主](../user-guides/OWNER.md) · [商家](../user-guides/MERCHANT.md) · [技师](../user-guides/TECHNICIAN.md) · [受限运营](../user-guides/OPERATOR.md) · [剩余验收](REMAINING_ACCEPTANCE.md)',
           '', '## 注册页面','']
    for page in pages:
        lines.append(f"- `{page['path']}` — {page['title']}；[源码](../../{page['source']})；[阅读指南](../../{page['guide']})。")
    lines+=['','## 环境模板变量','','只枚举三份受版本控制的模板，不读取.env、*.local或部署环境；不声称覆盖后端全部环境变量。模板字面布尔值仅说明示例取值，不代表运行默认值或已开启。其他值请查模板，真实密钥只在私有环境填写。']
    for file in TEMPLATES:
        lines+=['',f'### {file}','',f'[来源模板](../../{file})','']
        for row in configs:
            if row['source']==file:
                suffix='；模板值 `'+row['template_boolean']+'`' if row['template_boolean'] else ''
                lines.append('- `'+row['key']+'`'+suffix+'。')
    lines+=['','网络配置和启用前置条件见[网络运维](../operations/MINIAPP_NETWORK_ENVIRONMENTS.md)及[剩余验收](REMAINING_ACCEPTANCE.md)。','']
    text='\n'.join(lines);validate_links(root,OUTPUT,text)
    return {OUTPUT:text,MANIFEST:json.dumps(manifest,ensure_ascii=False,indent=2)+'\n'}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    try:
        outputs=build()
        for file,content in outputs.items():
            if args.check:
                if not (ROOT/file).is_file() or read(ROOT,file)!=content:raise ValueError('Stale handover inventory: '+file)
            else:(ROOT/file).parent.mkdir(parents=True,exist_ok=True);(ROOT/file).write_text(content,encoding='utf-8')
    except (ValueError,KeyError,FileNotFoundError) as error:
        print('Miniapp handover FAIL:',error);return 1
    print('Miniapp handover PASS; source inventory only, no runtime acceptance');return 0


if __name__=='__main__':raise SystemExit(main())
