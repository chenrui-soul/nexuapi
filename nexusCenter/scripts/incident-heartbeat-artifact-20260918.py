import pathlib,sys,paramiko,hashlib,zipfile,subprocess
sys.stdout.reconfigure(encoding='utf-8')
root=pathlib.Path(__file__).resolve().parents[1]
out=root/'backend/target/incident-heartbeat'
out.mkdir(parents=True,exist_ok=True)
client=paramiko.SSHClient();client.load_system_host_keys()
client.connect('8.218.238.141',username='root',key_filename=str(pathlib.Path.home()/'.ssh/nexus-deploy-ed25519'),timeout=8,banner_timeout=15)
try:
 s=client.open_sftp()
 path='src/main/java/com/nexusapi/server/modules/gateway/upstream/OpenAiUpstreamClient.java'
 remote_source=s.open('/opt/nexusCenter/backend/'+path).read().decode().replace('\r\n','\n')
 old='.map(ServerSentEvent::data)\n                                .filter(data -> data != null);'
 new='// Comment/ping frames have no data. Reactor map rejects null before\n                                // a downstream filter can run, so discard heartbeats before mapping.\n                                .filter(event -> event.data() != null)\n                                .map(ServerSentEvent::data);'
 assert old in remote_source,'Production source has diverged: inspect before patch'
 local=(root/'backend'/path).read_text(encoding='utf-8').replace('\r\n','\n')
 original=out/'original.jar'
 s.get('/opt/nexusCenter/backend/incident-heartbeat-original-20260918.jar',str(original))
 libs=out/'lib';libs.mkdir(exist_ok=True)
 classes=out/'production-classes';classes.mkdir(exist_ok=True)
 with zipfile.ZipFile(original) as jar:
  for entry in jar.infolist():
   if entry.filename.startswith('BOOT-INF/lib/') and entry.filename.endswith('.jar'):
    (libs/pathlib.PurePosixPath(entry.filename).name).write_bytes(jar.read(entry))
   elif entry.filename.startswith('BOOT-INF/classes/') and not entry.is_dir():
    dest=classes/entry.filename[len('BOOT-INF/classes/'):];dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(jar.read(entry))
 compiled=out/'compiled';compiled.mkdir(exist_ok=True)
 signatures=subprocess.check_output([r'C:\Program Files\Java\jdk-21.0.12\bin\javap.exe','-classpath',str(classes),'com.nexusapi.server.modules.gateway.upstream.OpenAiUpstreamClient'],text=True)
 image_signature=next(l for l in signatures.splitlines() if ' imageGeneration(' in l)
 print('PRODUCTION_IMAGE_METHOD',image_signature)
 if 'java.util.List<java.lang.String>' in image_signature:
  source=local
 else:
  source=remote_source.replace(old,new)
 java_source=out/'source/OpenAiUpstreamClient.java';java_source.parent.mkdir(exist_ok=True);java_source.write_text(source,encoding='utf-8')
 subprocess.run([r'C:\Program Files\Java\jdk-21.0.12\bin\javac.exe','--release','21','-encoding','UTF-8','-cp',str(classes)+';'+str(libs/'*'),'-d',str(compiled),str(java_source)],check=True)
 new_signatures=subprocess.check_output([r'C:\Program Files\Java\jdk-21.0.12\bin\javap.exe','-classpath',str(compiled),'com.nexusapi.server.modules.gateway.upstream.OpenAiUpstreamClient'],text=True)
 assert signatures==new_signatures,'Production public signatures must remain identical'
 patched=out/'app.jar';changed=[]
 with zipfile.ZipFile(original) as src,zipfile.ZipFile(patched,'w') as dst:
  for entry in src.infolist():
   replacement=compiled/entry.filename[len('BOOT-INF/classes/'):] if entry.filename.startswith('BOOT-INF/classes/') else None
   body=src.read(entry)
   if replacement is not None and replacement.is_file():body=replacement.read_bytes();changed.append(entry.filename)
   dst.writestr(entry,body)
 print('REPLACED_CLASSES',changed)
 assert changed and all('/OpenAiUpstreamClient' in name for name in changed)
 remote_dir='/opt/nexusCenter/incident-heartbeat-20260918'
 try:s.mkdir(remote_dir)
 except OSError:pass
 s.put(str(patched),remote_dir+'/app.jar')
 with s.open(remote_dir+'/Dockerfile','w') as f:f.write('FROM nexus-center-prod-app:before-heartbeat-20260918\nCOPY --chown=nexus:nexus app.jar /app/app.jar\n')
 with s.open(remote_dir+'/OpenAiUpstreamClient.java','w') as f:f.write(source)
 print('PATCHED_JAR_SHA256',hashlib.sha256(patched.read_bytes()).hexdigest())
 print('ONLY existing production client class family replaced; all other jar entries preserved')
finally:client.close()
