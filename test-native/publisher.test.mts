import test from'node:test';import assert from'node:assert/strict';import{assertCompatible,type ApkInfo}from'../scripts/publish-android-release.mts';
const cert='a'.repeat(64),hash='b'.repeat(64),oldHash='c'.repeat(64);const old:ApkInfo={packageName:'com.cbgpu.cbgpu_app',version:'1.0.10',buildNumber:11,certificateSha256:cert};const next={...old,version:'1.0.11',buildNumber:12};
test('same-key native upgrade preserves package identity',()=>assert.doesNotThrow(()=>assertCompatible(next,old,'1.0.11',12,hash,oldHash)));
test('different key, package, source version, downgrade and same-build replacement are rejected before writes',()=>{
 for(const variant of [{certificateSha256:'d'.repeat(64)},{packageName:'com.fake'},{version:'1.0.12'},{buildNumber:13}])assert.throws(()=>assertCompatible({...next,...variant},old,'1.0.11',12,hash,oldHash));
 assert.throws(()=>assertCompatible(next,{...old,buildNumber:13},'1.0.11',12,hash,oldHash));assert.throws(()=>assertCompatible(next,{...old,buildNumber:12},'1.0.11',12,hash,oldHash));assert.throws(()=>assertCompatible(next,old,'1.0.11',12,hash,oldHash,{buildNumber:12,sha256:oldHash}));
});
