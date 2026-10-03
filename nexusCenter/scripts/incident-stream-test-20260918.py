"""Temporary, expiring production key; verify real SSE completion and revoke in finally."""
import concurrent.futures
import datetime
import hashlib
import hmac
import json
import os
import pathlib
import secrets
import sys
import time
import uuid
import base64
import argparse
import paramiko
import requests

sys.stdout.reconfigure(encoding='utf-8')
parser=argparse.ArgumentParser()
parser.add_argument('--extended',action='store_true')
parser.add_argument('--focused',action='store_true')
parser.add_argument('--transport-only',action='store_true')
parser.add_argument('--reasoning',action='store_true')
args=parser.parse_args()
client=paramiko.SSHClient()
client.load_system_host_keys()
client.connect('8.218.238.141',username='root',key_filename=str(pathlib.Path.home()/'.ssh/nexus-deploy-ed25519'),timeout=8,banner_timeout=15)
def remote(command, data=None):
    si,so,se=client.exec_command(command,timeout=20)
    if data is not None: si.write(data);si.flush()
    si.channel.shutdown_write()
    out=so.read().decode();err=se.read().decode()
    if so.channel.recv_exit_status(): raise RuntimeError(err[:300])
    return out.strip()
def sql(query):
    return remote('docker exec -i nexus-center-prod-postgres-1 psql -X -qAt -U nexus -d nexus_api -v ON_ERROR_STOP=1',query)
key_id=str(uuid.uuid4())
api_key='sk-nx-v1_'+secrets.token_urlsafe(32)
report=[]
created=False
def test(label,model='gpt-6-astra',padding=0,spaces=False):
    rid='req_incident_'+uuid.uuid4().hex
    prompt='Reply with exactly OK.'
    if padding:
        prompt='Ignore the following diagnostic padding and reply with exactly OK.\n'+((' '*padding) if spaces else ('diagnostic-padding '*(padding//19)))+'\nReply OK.'
    if label.startswith('long-output'):
        prompt='Write a coherent article of about 900 words on testing distributed systems. Start immediately and output only the article.'
    if label.startswith('reasoning'):
        prompt='Find the smallest positive integer n such that n is divisible by 37, its decimal digits sum to 100, and every decimal digit is 1, 3, or 7. Prove minimality carefully. Work through the search before giving the final answer.'
    model_input=prompt
    if spaces and padding>20000000:
        # Match multi-item histories and stay below Jackson's per-string limit.
        model_input=[{'role':'user','content':prompt[i:i+1000000]} for i in range(0,len(prompt),1000000)]
        model_input.append({'role':'user','content':'Reply with exactly OK.'})
    payload={'model':model,'input':model_input,'stream':True,'max_output_tokens':1800 if label.startswith('long-output') else 256}
    if label.startswith('reasoning'):
        payload.update(reasoning={'effort':'high'},max_output_tokens=12000)
    body=json.dumps(payload).encode()
    row={'label':label,'request_id':rid,'model':model,'bytes':len(body)}
    start=time.monotonic();events=[];done=False
    try:
        with requests.Session() as session:
            session.trust_env=False
            with session.post('https://nexusapi.center/v1/responses',data=body,headers={'Authorization':'Bearer '+api_key,'Content-Type':'application/json','X-Request-Id':rid},stream=True,timeout=(120,200)) as response:
                row['http']=response.status_code; row['headers_s']=round(time.monotonic()-start,3)
                if response.status_code!=200:
                    row['error']=response.text[:350]
                else:
                    for line in response.iter_lines(chunk_size=1):
                        if not line.startswith(b'data:'):continue
                        try: item=json.loads(line[5:])
                        except ValueError:continue
                        event=item.get('type','');events.append(event)
                        if event=='response.output_text.delta' and 'first_delta_s' not in row:row['first_delta_s']=round(time.monotonic()-start,3)
                        if event=='response.completed':
                            done=True
                            usage=item.get('response',{}).get('usage') or {}
                            row['usage']={k:usage.get(k) for k in ('input_tokens','output_tokens','total_tokens')}
                            break
                        if event in ('response.failed','error'):row['failure_event']=item;break
    except Exception as exc:row['exception']=type(exc).__name__
    row.update(completed=done,seconds=round(time.monotonic()-start,3),event_count=len(events))
    print(json.dumps(row,ensure_ascii=False),flush=True)
    return row
try:
    data=json.loads(sql("SELECT json_build_object('user',user_id,'group',group_id) FROM request_logs WHERE request_id='req_fcf6a6d6499f497c9c0b100f4ae1995a';"))
    secret=remote('docker exec nexus-center-prod-app-1 printenv NEXUS_API_KEY_HMAC_KEY_V1')
    digest=hmac.new(base64.b64decode(secret),('api-key:v1:'+api_key).encode(),hashlib.sha256).hexdigest()
    sql(f"INSERT INTO api_keys(id,user_id,name,key_prefix,key_suffix,key_hash,key_hash_version,status,default_group_id,service_group_id,allowed_model_ids,allowed_group_ids,ip_allowlist,rpm_limit,concurrency_limit,credit_limit,expires_at) VALUES ('{key_id}','{data['user']}','Incident SSE regression 20260918','{api_key[:16]}','{api_key[-8:]}',decode('{digest}','hex'),1,'active','{data['group']}','{data['group']}','[]','[\"{data['group']}\"]','[]',60,8,100,now()+interval '20 minutes');")
    created=True
    if not args.focused and not args.transport_only and not args.reasoning:
        report.append(test('sequential-astra'))
        report.append(test('sequential-sol','gpt-5.6-sol'))
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            report.extend(pool.map(test,['parallel-1','parallel-2','parallel-3']))
        report.append(test('large-200kb',padding=200000))
    if args.extended:
        report.append(test('large-350kb',padding=350000))
        report.append(test('large-1600kb',padding=1600000))
        report.append(test('transport-25mb-spaces',padding=25198000,spaces=True))
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            report.extend(pool.map(test,['long-output-1','long-output-2','long-output-3']))
    if args.focused:
        report.append(test('transport-25mb-spaces',padding=25198000,spaces=True))
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            report.extend(pool.map(test,['long-output-1','long-output-2','long-output-3']))
    if args.transport_only:
        report.append(test('transport-25mb-multi-item',padding=25198000,spaces=True))
    if args.reasoning:
        report.append(test('reasoning-heartbeat'))
    ids=','.join("'"+r['request_id']+"'" for r in report)
    db_rows=sql(f"SELECT json_build_object('id',r.request_id,'status',r.status_code,'error',r.platform_error_code,'duration_ms',r.duration_ms,'retries',r.retry_count,'supplier',s.code) FROM request_logs r LEFT JOIN suppliers s ON s.id=r.supplier_id WHERE r.request_id IN ({ids});")
    print('DB_LOGS',db_rows,flush=True)
finally:
    if created:
        sql(f"UPDATE api_keys SET status='revoked',revoked_at=now(),status_changed_at=now(),version=version+1 WHERE id='{key_id}';")
        print('TEMPORARY_KEY_REVOKED',key_id,flush=True)
    client.close()
    dest=pathlib.Path(__file__).parent/'log'/('incident-stream-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')+'.json')
    dest.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print('REPORT',dest)
sys.exit(0 if report and all(row.get('completed') for row in report) else 1)
