/** Native Android build only; existing release signing key is required. */
import fs from 'node:fs';import path from 'node:path';import os from 'node:os';import{spawnSync}from'node:child_process';import{fileURLToPath}from'node:url';
const root=fileURLToPath(new URL('..',import.meta.url));const debug=process.argv.includes('--debug');
if(!debug&&!fs.existsSync(path.join(root,'android/key.properties')))throw Error('The existing android/key.properties and release keystore are required. No debug fallback.');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const env={...process.env};const cargo=path.join(os.homedir(),'.cargo/bin',process.platform==='win32'?'cargo.exe':'cargo');if(!env.CARGO&&fs.existsSync(cargo))env.CARGO=cargo;
const result=spawnSync(java,['-classpath',path.join(root,'android/gradle/wrapper/gradle-wrapper.jar'),'org.gradle.wrapper.GradleWrapperMain','-p',path.join(root,'android'),debug?'assembleDebug':'assembleRelease'],{cwd:root,env,stdio:'inherit',shell:false});
if(result.error)throw Error('Native build tools unavailable. Install JDK17, Node22, Rust Android targets, SDK36 and NDK28.2.13676358.');process.exitCode=result.status??1;
