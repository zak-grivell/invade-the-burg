const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const html=readFileSync('src/main/resources/browser/chat-recap.html','utf8');
const source=html.match(/<script>([\s\S]*?)<\/script>/)[1];
const tick=()=>new Promise(setImmediate);
function fixture(autoPlay=true, nativeHost=false){
 const elements={}, frames=[];
 let completions=0, hostRequests=0, inNativeCall=false;
 for(const id of ['voice','background','play','caption','status','video-error'])elements[id]={
  hidden:true,disabled:true,readyState:0,error:null,paused:true,ended:false,plays:0,currentTime:0,duration:10,listeners:{},failures:[],
  addEventListener(event,fn){(this.listeners[event]??=[]).push(fn)},
  emit(event){for(const fn of this.listeners[event]||[])fn()},
  play(){this.plays++;if(nativeHost && !inNativeCall)return Promise.reject(Object.assign(new Error(),{name:'NotAllowedError'}));const failure=this.failures.shift();if(failure)return Promise.reject(Object.assign(new Error(),{name:failure}));
   const changed=this.paused;this.paused=false;if(changed)this.emit('play');return Promise.resolve()},
  pause(){const changed=!this.paused;this.paused=true;if(changed)this.emit('pause')}
 };
 const document={getElementById:id=>elements[id],addEventListener(){},hidden:false};
 const window={addEventListener(){},reelBreakRecapReady(){},reelBreakRecapFinished(){completions++;}};
 if(nativeHost)window.reelBreakRecapRequestPlayback=()=>{hostRequests++;};
 const script=source.replace('/*RECAP_DATA*/',JSON.stringify({text:'This is the response.',video:'background.webm',autoPlay}));
 vm.runInNewContext(script,{document,window,TextDecoder,Uint8Array,atob,requestAnimationFrame:fn=>frames.push(fn)});
 return {e:elements,hostRequests:()=>hostRequests,
  async nativeStart(){inNativeCall=true;window.__reelBreakStartRecapFromHost();inNativeCall=false;await tick();},
  completions:()=>completions,activate:window.__reelBreakActivateRecap,
  ready(){elements.voice.readyState=2;elements.background.readyState=2;elements.voice.emit('canplay')},
  async layout(){for(let i=0;i<2;i++)for(const fn of frames.splice(0))fn();await tick();}
 };
}
(async()=>{
 assert.doesNotMatch(html.match(/<audio\b[^>]*>/)[0],/\bcontrols\b/,'no native timeline');
 assert.match(html,/audio\{display:none!important\}/,'audio stays hidden through readiness and errors');
 {
  const f=fixture();f.ready();assert.equal(f.e.voice.plays,0);
  f.activate(true);await f.layout();
  assert.equal(f.e.voice.plays,1);assert.equal(f.e.background.plays,1);
  assert.equal(f.e.play.hidden,true,'successful playback has no bottom Play bar');
  assert.equal(f.e.voice.hidden,true);assert.equal(f.e.voice.loop,false);assert.equal(f.e.background.loop,true);
  f.activate(true);f.ready();await f.layout();
  assert.equal(f.e.voice.plays,1,'document lifecycle notifications must not restart narration');
  f.e.voice.ended=true;f.e.voice.paused=true;f.e.voice.emit('ended');await tick();
  assert.equal(f.completions(),1,'narration end closes the recap');
  assert.equal(f.e.voice.paused,true);assert.equal(f.e.background.paused,true);
  const starts=f.e.voice.plays;
  f.e.voice.emit('ended');f.activate(true);f.ready();await f.layout();
  assert.equal(f.completions(),1,'completion callback is delivered once');
  assert.equal(f.e.voice.plays,starts,'completed recap must never restart');
 }
 {
  const f=fixture();f.e.background.failures=['NotAllowedError'];f.ready();f.activate(true);await f.layout();
  assert.equal(f.e.voice.paused,true,'narration cannot continue after video policy rejection');
  await f.layout();
  assert.equal(f.e.background.paused,false,'retry after layout starts the video');
  assert.equal(f.e.voice.paused,false);assert.equal(f.e.play.hidden,true);
 }
 {
  const f=fixture();f.e.background.failures=['NotAllowedError','NotAllowedError'];f.ready();f.activate(true);await f.layout();await f.layout();
  assert.equal(f.e.voice.paused,true);assert.equal(f.e.background.paused,true);
  assert.equal(f.e.play.hidden,false,'both stop and offer one retry if policy still blocks');
  const attempts=f.e.background.plays;await f.layout();await f.layout();assert.equal(f.e.background.plays,attempts,'no autoplay retry loop');
  f.e.play.emit('click');await tick();
  assert.equal(f.e.voice.paused,false);assert.equal(f.e.background.paused,false);assert.equal(f.e.play.hidden,true);
 }
 {
  const f=fixture();f.ready();f.activate(true);f.activate(false);await f.layout();
  assert.equal(f.e.voice.plays,0,'stale activation cannot start a hidden recap');
 }
 {
  const f=fixture(false);f.ready();f.activate(true);await f.layout();f.e.play.emit('click');await tick();
  assert.equal(f.e.voice.plays,1);assert.equal(f.e.background.plays,1,'one video request per user click');
 }
 {
  const f=fixture();f.activate(true);f.e.voice.readyState=2;f.e.voice.emit('canplay');await f.layout();
  assert.equal(f.e.voice.plays,0,'wait for footage before starting narration');
  f.ready();await tick();assert.equal(f.e.voice.paused,false);assert.equal(f.e.background.paused,false);
  f.e.background.error={code:4};f.e.background.emit('error');
  assert.equal(f.e.voice.paused,true,'media errors stop narration');assert.equal(f.e['video-error'].hidden,false);
 }
 {
  const f=fixture();f.ready();f.activate(true);await f.layout();f.activate(false);f.e.voice.pause();f.e.background.pause();
  f.activate(true);await f.layout();
  assert.equal(f.e.voice.paused,false);assert.equal(f.e.background.paused,false,'returning resumes both tracks');
 }
 {
  const f=fixture();f.e.voice.currentTime=17;f.e.background.currentTime=240;
  f.ready();await tick();
  assert.equal(f.e.voice.plays,0,'async generation must not start a background recap');
  assert.equal(f.e.background.plays,0);
  f.activate(true);await f.layout();
  assert.equal(f.e.voice.currentTime,0,'first reveal starts narration at zero');
  assert.equal(f.e.background.currentTime,0,'first reveal starts footage at zero');
  f.e.voice.currentTime=3;f.e.background.currentTime=6;
  f.activate(false);
  assert.equal(f.e.voice.paused,true);assert.equal(f.e.background.paused,true);
  f.activate(true);await f.layout();
  assert.equal(f.e.voice.currentTime,3,'returning to the same recap preserves intentional progress');
  assert.equal(f.e.background.currentTime,6);
 }
 {
  const f=fixture();let resolve;
  const deferred=new Promise(done=>{resolve=done});
  const nativePlay=f.e.background.play.bind(f.e.background);let first=true;
  f.e.background.play=()=>{const result=nativePlay();if(first){first=false;return deferred;}return result;};
  f.ready();f.activate(true);await f.layout();
  f.activate(false);f.activate(true);await f.layout();
  resolve();await tick();await f.layout();
  assert.equal(f.e.voice.paused,false,'a late async start must not strand the reselected recap');
  assert.equal(f.e.background.paused,false);
 }
 {
  const f=fixture(true,true);f.ready();f.activate(true);await f.layout();
  assert.equal(f.hostRequests(),1,'autoplay must request the native gesture bridge after layout');
  assert.equal(f.e.voice.plays,0,'RAF cannot directly start policy-protected media');
  await f.nativeStart();
  assert.equal(f.e.voice.paused,false);assert.equal(f.e.background.paused,false);
  assert.equal(f.e.play.hidden,true,'native autoplay requires no Play click');
  f.ready();f.activate(true);await f.layout();
  assert.equal(f.hostRequests(),1,'repeated readiness cannot duplicate the native start');
 }
 {
  const f=fixture(true,true);f.ready();f.activate(true);await f.layout();f.activate(false);
  await f.nativeStart();
  assert.equal(f.e.voice.plays,0,'a delayed native callback must not start a hidden recap');
 }
 console.log('PASS: no timeline, synchronized autoplay, layout retry, bounded failure recovery, automatic close, hidden cancellation, and paired resume');
})().catch(e=>{console.error(e);process.exitCode=1});
