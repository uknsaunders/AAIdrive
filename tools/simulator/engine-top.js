// i3D desk simulator: stands in for the phone and the car. Runs inside the car page, before its own script.
(function(){
const RealDate=Date;let off=0;
window.Date=class extends RealDate{constructor(...a){if(a.length)super(...a);else super(RealDate.now()+off)}static now(){return RealDate.now()+off}};
const post=(type,data)=>{try{parent.postMessage(Object.assign({i3sim:type},data||{}),'*')}catch(e){}};
const say=t=>post('log',{text:t});
const TRACKS=[['Arseblog Arsecast','Arseblog','Episode 412','#c2410c'],['Mr. Brightside','The Killers','Hot Fuss','#1d4ed8'],['Dreams','Fleetwood Mac','Rumours','#0f766e'],['Take On Me','a-ha','Hunting High and Low','#7e22ce']];
const APPS=['Spotify','Amazon Music','BBC Sounds','YouTube Music'],LISTS=['Liked Songs','Discover Weekly','Daily Mix 1','On Repeat','Release Radar','Jersey drive'];
const two=(a,b)=>[{number:a,label:'Mobile'},{number:b,label:'Work'}],one=a=>[{number:a,label:'Mobile'}];
const EVERYONE=[['Andy Le Brun','07700 900111'],['Garage (Bagot Road)','01534 000111'],['Mum','07700 900456'],['Sarah','07700 900123','01534 555010'],['Tom','07700 900789','01534 555020'],['Vet','01534 000222']]
  .map(p=>({name:p[0],number:p[1],numbers:p[2]?two(p[1],p[2]):one(p[1])}));
const sim={soc:78,rangeKm:118,speedKmh:0,music:true,playing:true,track:0,app:0,posMs:60000,notes:[],call:null,access:true,contacts:true,lat:49.1885,lon:-2.0335,heading:10,route:null,along:0,level:0};
window.__sim=sim;window.__simPost=post;window.__simSay=say;window.__simTracks=TRACKS;window.__simApps=APPS;
// moves the clock to midday or late evening, and says by how much it moved
window.__simTime=day=>{const n=new RealDate();n.setUTCHours(day?12:22,30,0,0);const was=off;off=n.getTime()-RealDate.now();return off-was};
const hav=(a,b)=>{const R=6371000,r=Math.PI/180,dLat=(b[1]-a[1])*r,dLon=(b[0]-a[0])*r,x=Math.sin(dLat/2)**2+Math.cos(a[1]*r)*Math.cos(b[1]*r)*Math.sin(dLon/2)**2;return 2*R*Math.asin(Math.sqrt(x))};
window.__simHav=hav;
// the internet, as far as the car page is concerned: a made-up route, weather and one charger. Map pictures are not loaded.
window.fetch=async function(u){u=String(u);
  const ok=j=>({ok:true,status:200,json:async()=>j});
  if(u.includes('router.project-osrm.org')){
    const m=u.match(/driving\/([-\d.]+),([-\d.]+);([-\d.]+),([-\d.]+)/),a=[+m[1],+m[2]],b=[+m[3],+m[4]],corner=[a[0],b[1]],coords=[];
    for(let i=0;i<=10;i++)coords.push([a[0],a[1]+(corner[1]-a[1])*i/10]);
    for(let i=1;i<=10;i++)coords.push([corner[0]+(b[0]-corner[0])*i/10,b[1]]);
    const d1=Math.max(60,hav(a,corner)),d2=Math.max(60,hav(corner,b)),north=b[1]>=a[1],east=b[0]>=a[0];
    sim.pending=coords;
    return ok({routes:[{geometry:{type:'LineString',coordinates:coords},duration:(d1+d2)/11,legs:[{steps:[
      {distance:d1,name:'Rue du Parc',maneuver:{type:'depart',modifier:'straight'}},
      {distance:d2,name:'La Grande Route de Faldouet',maneuver:{type:'turn',modifier:north===east?'right':'left'}},
      {distance:0,name:'',maneuver:{type:'arrive'}}]}]}]})}
  if(u.includes('open-meteo'))return ok({current:{weather_code:2,is_day:1}});
  if(u.includes('overpass')){const q=decodeURIComponent(u);return ok({elements:q.includes('charging')?[{lat:sim.lat+0.012,lon:sim.lon+0.01,tags:{name:'Gorey Pier charger'}}]:[]})}
  throw new Error('not loaded in the simulator');
};
const people=()=>sim.contacts?{contacts:EVERYONE.filter(p=>p.name==='Sarah'||p.name==='Mum'),everyone:EVERYONE,recentCalls:[{name:'Sarah',number:'+441534555010'},{name:'Garage (Bagot Road)',number:'01534 000111'},{name:'01534 000333',number:'01534 000333'}]}:{contacts:[],everyone:[],recentCalls:[]};
const musicTop=()=>window.i3music({rows:APPS.map((a,i)=>a+(i===sim.app&&sim.music?' (playing)':'')),top:true,played:false});
window.I3Native={
  log(){},
  speak(t){say('Spoken aloud: "'+t+'"')},
  menu(){const p=people();return JSON.stringify(Object.assign({places:[{name:'Home',lat:49.1772,lon:-2.0201},{name:'Work',lat:49.1840,lon:-2.1070}],recents:[{name:'Waitrose St Helier',lat:49.1890,lon:-2.1120},{name:'Gorey Castle',lat:49.1995,lon:-2.0195}],
    videos:[{id:'abc12345678',list:'',title:'Arsenal highlights'}],canSeeContacts:sim.contacts,canCall:sim.contacts},p))},
  navStarted(lat,lon,name){say('Route started to '+(name||'the chosen place'))},
  navigationEnded(){say('Route ended');sim.route=null},
  search(){say('The car\'s own letter wheel would open here. It is the car\'s screen, so it is not in the simulator.')},
  answer(){if(sim.call){sim.call.ongoing=true;sim.call.since=Date.now();say('Answered the call')}},
  hangup(){if(sim.call){say(sim.call.ongoing?'Hung up':'Declined the call');sim.call=null}},
  dial(number,name){sim.call={name:name||number,text:'',number:number,outgoing:true,ongoing:true,since:Date.now()};say('Dialled '+(name?name+' on ':'')+number)},
  media(c){if(!sim.music)return;if(c==='toggle'){sim.playing=!sim.playing;say(sim.playing?'Music: play':'Music: pause')}else{sim.track=(sim.track+(c==='next'?1:TRACKS.length-1))%TRACKS.length;sim.posMs=0;say('Music: '+(c==='next'?'next':'previous')+' track')}},
  musicOpen(){sim.level=0;setTimeout(musicTop,350)},
  musicPick(i){setTimeout(()=>{
    if(sim.level===0){sim.level=1;sim.pickApp=i;window.i3music({rows:['‹ Back'].concat(LISTS),top:false,played:false})}
    else if(i===0){sim.level=0;musicTop()}
    else{sim.app=sim.pickApp;sim.music=true;sim.playing=true;sim.track=i%TRACKS.length;sim.posMs=0;say('Playing "'+LISTS[i-1]+'" on '+APPS[sim.app]);window.i3music({rows:[],top:false,played:true})}},350)},
  video(id){if(sim.speedKmh>0){say('Video refused: the car is moving');return 'moving'}say('The video would take over the screen here (not shown in the simulator)');return 'sent'}
};
})();
