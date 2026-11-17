/** Local verified publication. Default is read-only; --publish performs the reviewed atomic switch. */
import fs from'node:fs/promises';import{createReadStream,constants}from'node:fs';import path from'node:path';import os from'node:os';import crypto from'node:crypto';import{spawnSync}from'node:child_process';import{fileURLToPath,pathToFileURL}from'node:url';
export type ApkInfo={packageName:string;version:string;buildNumber:number;certificateSha256:string};
export function assertCompatible(next:ApkInfo,old:ApkInfo,version:string,build:number,nextHash:string,oldHash:string,manifest?:{buildNumber:number;sha256?:string}){
 if(next.packageName!=='com.cbgpu.cbgpu_app'||old.packageName!==next.packageName||next.version!==version||next.buildNumber!==build)throw Error('APK package/version does not match native source.');
 if(!/^[a-f0-9]{64}$/.test(next.certificateSha256)||next.certificateSha256!==old.certificateSha256)throw Error('Signing certificate mismatch; current users could not update.');
 if(old.buildNumber>build||old.buildNumber===build&&oldHash!==nextHash)throw Error('Refusing a downgrade or different APK under the same build number.');
 if(manifest&&(manifest.buildNumber>build||manifest.buildNumber===build&&manifest.sha256!==nextHash))throw Error('Published manifest already advertises a newer or different build.');
}
async function digest(file:string){const hash=crypto.createHash('sha256');for await(const chunk of createReadStream(file))hash.update(chunk);return hash.digest('hex');}
function sdkRoot(root:string){if(process.env.ANDROID_HOME||process.env.ANDROID_SDK_ROOT)return(process.env.ANDROID_HOME||process.env.ANDROID_SDK_ROOT)!;const props=fileURLToPath(new URL('../android/local.properties',import.meta.url));return import('node:fs').then(({readFileSync})=>{const value=readFileSync(props,'utf8').match(/^sdk\.dir=(.+)$/m)?.[1];if(!value)throw Error('Configure ANDROID_HOME for verification.');return value.replace(/\\:/g,':').replace(/\\\\/g,'\\');});}
function tool(command:string,args:string[],env:NodeJS.ProcessEnv){const r=spawnSync(command,args,{env,encoding:'utf8',shell:false,maxBuffer:4*1024*1024});if(r.error||r.status!==0)throw Error('APK verification failed; existing downloads remain unchanged.');return r.stdout;}
async function inspect(apk:string,sdk:string,env:NodeJS.ProcessEnv):Promise<ApkInfo>{
 const tools=path.join(sdk,'build-tools','36.0.0');const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
 const signed=tool(java,['-jar',path.join(tools,'lib/apksigner.jar'),'verify','--print-certs',apk],env);const certs=[...signed.matchAll(/Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)/g)];if(certs.length!==1)throw Error('Expected exactly one verified APK signing certificate.');
 const badging=tool(path.join(tools,process.platform==='win32'?'aapt.exe':'aapt'),['dump','badging',apk],env);const info=badging.match(/package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/);if(!info)throw Error('Cannot verify APK package identity.');return{packageName:info[1],buildNumber:Number(info[2]),version:info[3],certificateSha256:certs[0][1].toLowerCase()};
}
async function atomic(source:string,target:string){const temp=path.join(path.dirname(target),'.release-'+crypto.randomBytes(12).toString('hex'));try{await fs.copyFile(source,temp,constants.COPYFILE_EXCL);await fs.chmod(temp,0o644);const file=await fs.open(temp,'r');try{await file.sync()}finally{await file.close()}await fs.rename(temp,target)}finally{await fs.unlink(temp).catch(()=>{})}}
export async function main(){
 const root=fileURLToPath(new URL('..',import.meta.url));const apk=path.join(root,'android/app/build/outputs/apk/release/app-release.apk');const destination=process.env.CBGPU_DOWNLOADS_DIR||'/home/cbgpu/CBGPU-live/FrontEnd/cbgpu/public/downloads';const old=path.join(destination,'cbgpu.apk'),manifestPath=path.join(destination,'android-release.json');
 const lock=path.join(destination,'.android-release-lock');const publishing=process.argv.includes('--publish');
 if(publishing){await fs.mkdir(lock,{mode:0o700});}
 try{
 const versionProps=await fs.readFile(path.join(root,'android/version.properties'),'utf8');const version=versionProps.match(/^versionName=(\d+\.\d+\.\d+)$/m)?.[1],build=Number(versionProps.match(/^versionCode=(\d+)$/m)?.[1]);if(!version||!Number.isSafeInteger(build)||build<1)throw Error('Invalid native Android version metadata.');
 const env={...process.env};if(env.JAVA_HOME)env.PATH=path.join(env.JAVA_HOME,'bin')+path.delimiter+(env.PATH||'');const sdk=await sdkRoot(root);
 const[next,previous,nextHash,oldHash]=await Promise.all([inspect(apk,sdk,env),inspect(old,sdk,env),digest(apk),digest(old)]);
 let manifest;try{manifest=JSON.parse(await fs.readFile(manifestPath,'utf8'))}catch(error){if((error as NodeJS.ErrnoException).code!=='ENOENT')throw error;}
 assertCompatible(next,previous,version,build,nextHash,oldHash,manifest);
 if(!process.argv.includes('--publish')){console.log(JSON.stringify({verified:true,published:false,version,buildNumber:build,sha256:nextHash,certificateSha256:next.certificateSha256}));return;}
 const backup=path.join(os.homedir(),'.local/share/cbgpu-android-rollback');await fs.mkdir(backup,{recursive:true,mode:0o700});const saved=path.join(backup,oldHash+'.apk');try{await fs.copyFile(old,saved,constants.COPYFILE_EXCL);await fs.chmod(saved,0o600)}catch(error){if((error as NodeJS.ErrnoException).code!=='EEXIST')throw error;}if(await digest(saved)!==oldHash)throw Error('Rollback APK verification failed.');
 await atomic(apk,path.join(destination,'cbgpu-'+version+'.apk'));await atomic(apk,old);
 const data={version,buildNumber:build,sha256:nextHash,notes:'공식 CBGPU 브랜드와 Kotlin·Rust 기반 Android 앱입니다. 기존 계정과 앱 데이터는 유지됩니다.'};
 const stage=path.join(destination,'.manifest-'+crypto.randomBytes(12).toString('hex'));try{await fs.writeFile(stage,JSON.stringify(data)+'\n',{mode:0o644,flag:'wx'});const fd=await fs.open(stage,'r');try{await fd.sync()}finally{await fd.close()}await fs.rename(stage,manifestPath)}finally{await fs.unlink(stage).catch(()=>{})}
 console.log('Published verified Android '+version+' (build '+build+'); previous APK preserved for rollback.');
 }finally{if(publishing)await fs.rmdir(lock);}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href)await main();
