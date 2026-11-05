#!/usr/bin/env python3
"""Publish a locally built APK only after package/version/signature verification."""
import hashlib,json,os,re,shutil,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parent.parent
apk=root/'build/app/outputs/flutter-apk/app-release.apk'
dest=Path(os.environ.get('CBGPU_DOWNLOADS_DIR','/home/cbgpu/CBGPU-live/FrontEnd/cbgpu/public/downloads'))
match=re.search(r'^version: (\d+\.\d+\.\d+)\+(\d+)$',(root/'pubspec.yaml').read_text(),re.M)
if not match:raise SystemExit('Invalid package version')
version,build=match.group(1),int(match.group(2));old=dest/'cbgpu.apk'
if not apk.is_file() or not old.is_file():raise SystemExit('New APK and existing signed APK are required')
image='ghcr.io/cirruslabs/flutter@sha256:46691e311715845de03a3ba4753a475476936805b29431b1f00f1816981033f8'
script='''set -euo pipefail
signer=$(find /sdk/build-tools -name apksigner -type f | sort -V | tail -n 1)
aapt=$(find /sdk/build-tools -name aapt -type f | sort -V | tail -n 1)
"$signer" verify --print-certs /apk/new.apk
"$signer" verify --print-certs /apk/old.apk
"$aapt" dump badging /apk/new.apk | sed -n '1p'
'''
r=subprocess.run(['docker','run','--rm','--network','none','-v',str(apk)+':/apk/new.apk:ro','-v',str(old.resolve())+':/apk/old.apk:ro','-v','cbgpu-android-sdk:/sdk:ro','--entrypoint','bash',image,'-lc',script],text=True,capture_output=True)
if r.returncode:raise SystemExit('APK verification failed; existing download remains unchanged')
certs=re.findall(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)',r.stdout)
if len(certs)!=2 or certs[0]!=certs[1]:raise SystemExit('Signing certificate mismatch; existing users could not update')
if not re.search(r"package: name='com.cbgpu.cbgpu_app' versionCode='"+str(build)+r"' versionName='"+re.escape(version)+r"'",r.stdout):raise SystemExit('APK package/version does not match source')
def digest(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
sha=digest(apk);manifest=dest/'android-release.json'
if manifest.exists():
 previous=json.loads(manifest.read_text())
 if previous['buildNumber']>build or previous['buildNumber']==build and previous.get('sha256')!=sha:raise SystemExit('Refusing to replace a newer or differently signed build number')
backup=Path.home()/'.local/share/cbgpu-android-rollback';backup.mkdir(parents=True,exist_ok=True,mode=0o700)
previous_sha=digest(old);saved=backup/(previous_sha+'.apk')
if not saved.exists():shutil.copyfile(old,saved);saved.chmod(0o600)
def publish(source,target):
 fd,name=tempfile.mkstemp(dir=dest,prefix='.release-');os.close(fd)
 try:shutil.copyfile(source,name);os.chmod(name,0o644);os.replace(name,target)
 finally:Path(name).unlink(missing_ok=True)
publish(apk,dest/('cbgpu-'+version+'.apk'));publish(apk,old)
metadata={'version':version,'buildNumber':build,'sha256':sha,'notes':'새 버전이 있으면 앱에서 업데이트를 안내해요. 기존 계정과 앱 데이터는 그대로 유지됩니다.'}
fd,name=tempfile.mkstemp(dir=dest,prefix='.manifest-')
with os.fdopen(fd,'w') as f:json.dump(metadata,f,ensure_ascii=False);f.write('\n');f.flush();os.fsync(f.fileno())
os.chmod(name,0o644);os.replace(name,manifest)
print('Published verified Android '+version+' (build '+str(build)+'); previous APK preserved for rollback.')
