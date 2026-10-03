date -Is
free -m
vmstat 1 3
docker stats --no-stream --format '{{.Name}}|{{.MemUsage}}|{{.CPUPerc}}'
python3 - <<'PY'
import subprocess,json
env=json.loads(subprocess.check_output(['docker','inspect','nexus-center-prod-redis-1']).decode())[0]['Config']['Env']
# Fetch Redis password from app environment internally; never echo it.
env=json.loads(subprocess.check_output(['docker','inspect','nexus-center-prod-app-1']).decode())[0]['Config']['Env']
password=next(v.split('=',1)[1] for v in env if v.startswith('REDIS_PASSWORD='))
for command in [('GET','nexus:gateway:channel:1c50150e-7322-402f-94a1-5fdc5c0fbcf6:concurrency'),('TTL','nexus:gateway:channel:1c50150e-7322-402f-94a1-5fdc5c0fbcf6:concurrency')]:
 p=subprocess.run(['docker','exec','-e','REDISCLI_AUTH='+password,'nexus-center-prod-redis-1','redis-cli','--raw']+list(command),stdout=subprocess.PIPE,stderr=subprocess.PIPE,universal_newlines=True)
 print('CHANNEL_LEASE',command[0],p.stdout.strip())
logs=subprocess.run(['docker','logs','--since','8m','nexus-center-prod-app-1'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,universal_newlines=True)
for line in (logs.stdout+'\n'+logs.stderr).splitlines():
 try:
  x=json.loads(line)
  if x.get('level') in ('ERROR','WARN'):print(x.get('@timestamp'),x.get('message'))
 except ValueError:pass
PY
