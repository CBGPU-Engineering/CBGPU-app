interface NativeApi {
 isNative:true; platform:'android';
 setNavigation(opts:Record<string,unknown>):Promise<unknown>;getAppInfo():Promise<unknown>;
 scheduleAlarm(opts:Record<string,unknown>):Promise<unknown>;cancelAlarm(id:string):Promise<unknown>;cancelAllAlarms():Promise<unknown>;listAlarms():Promise<unknown>;
 requestNotificationPermission():Promise<unknown>;syncSession(opts:Record<string,unknown>):Promise<unknown>;clearSession():Promise<unknown>;
 loginEduset(opts?:Record<string,unknown>):Promise<unknown>;getPersistedSession():Promise<unknown>;downloadMaterial(opts:Record<string,unknown>):Promise<unknown>;
 setTimetableSelection(opts:{school_id:string;grade:number;class_num:number;expected_user_id:string}):Promise<unknown>;
 _resolve(callId:string,ok:boolean,result:unknown):void;
}
interface Window {CBGPUNative?:NativeApi}
declare const CBGPUNativeChannel:{postMessage(message:string):void};
(function(){
 if(/^\/(?:api|backend|downloads|_next|static)(?:\/|$)/.test(location.pathname)||/\.(?:html?|svg|xml|pdf|m?js|json|apk)$/i.test(location.pathname))return;
 if(location.origin!=='https://app.cbgpu.com'||window.top!==window||window.CBGPUNative)return;
 const pending:Record<string,{resolve:(value:unknown)=>void;reject:(reason:unknown)=>void}>={};let seq=0;const epoch=Date.now().toString(36)+Math.random().toString(36).slice(2);
 function call(action:string,payload:Record<string,unknown>={}){return new Promise<unknown>((resolve,reject)=>{if(Object.keys(pending).length>=256){reject({error:'Too many pending native requests'});return}const callId=epoch+'-c'+seq++;pending[callId]={resolve,reject};CBGPUNativeChannel.postMessage(JSON.stringify({callId,action,payload}));});}
 window.CBGPUNative={isNative:true,platform:'android',setNavigation:opts=>call('setNavigation',opts),getAppInfo:()=>call('getAppInfo'),scheduleAlarm:opts=>call('scheduleAlarm',opts),cancelAlarm:id=>call('cancelAlarm',{id}),cancelAllAlarms:()=>call('cancelAllAlarms'),listAlarms:()=>call('listAlarms'),requestNotificationPermission:()=>call('requestNotificationPermission'),syncSession:opts=>call('syncSession',opts),clearSession:()=>call('clearSession'),loginEduset:(opts={})=>call('loginEduset',opts),getPersistedSession:()=>call('getPersistedSession'),downloadMaterial:opts=>call('downloadMaterial',opts),setTimetableSelection:opts=>call('setTimetableSelection',opts),_resolve(callId,ok,result){const p=pending[callId];if(!p)return;delete pending[callId];if(ok)p.resolve(result);else p.reject(result)}};
 window.addEventListener('cbgpu-school-changed',()=>{void call('invalidateSchoolWidgets').catch(()=>{})});
 window.dispatchEvent(new Event('cbgpu-native-ready'));
})();
