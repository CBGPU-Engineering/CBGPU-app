import assert from 'node:assert/strict';import fs from 'node:fs';import test from 'node:test';
const root=new URL('../',import.meta.url);const read=(p:string)=>fs.readFileSync(new URL(p,root),'utf8');
test('Android mark preserves supplied brand path and uniform adaptive scaling',()=>{
 const source=read('assets/brand/symbol_black.svg').match(/\sd="([^"]+)"/)![1];
 for(const name of ['cbgpu_brand_symbol','cbgpu_brand_symbol_black','cbgpu_brand_symbol_white','ic_launcher_foreground','ic_notification_cbgpu']){
  const xml=read('android/app/src/main/res/drawable/'+name+'.xml');assert.equal(xml.match(/android:pathData="([^"]+)"/)![1],source);assert.match(xml,/android:translateX="24" android:translateY="24"/);
 }
 const adaptive=read('android/app/src/main/res/drawable/ic_launcher_foreground.xml');assert.match(adaptive,/android:scaleX="0.1300813" android:scaleY="0.1300813"/);

});
