// i3D desk simulator: runs after the car page's own script. Feeds it twice a second and takes orders from the outer page.
(function(){
const sim=window.__sim,post=window.__simPost,say=window.__simSay,TRACKS=window.__simTracks,APPS=window.__simApps,hav=window.__simHav;
const art=c=>'data:image/svg+xml,'+encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 80 80"><rect width="80" height="80" fill="'+c+'"/><circle cx="40" cy="40" r="20" fill="none" stroke="#fff" stroke-width="5" opacity=".85"/><circle cx="40" cy="40" r="4" fill="#fff"/></svg>');
let lastArt=-2,lastMap='';
function drawMap(){
  const m=document.getElementById('map');if(!m)return;const w=m.clientWidth,h=m.clientHeight,day=document.body.classList.contains('daymap'),hasRoute=!!route,key=[w,h,day,hasRoute].join();
  if(key===lastMap&&document.getElementById('fakemap'))return;lastMap=key;
  const C=day?{bg:'#ece8df',sea:'#a9d3ea',park:'#cfe6bf',r1:'#cfc8bb',r2:'#ffffff',txt:'#5d6670',seaT:'#3d7fa8',route:'#1668e3'}:{bg:'#0f1822',sea:'#0a2740',park:'#102a24',r1:'#1d2a38',r2:'#2c3d50',txt:'#6f8296',seaT:'#3d6a94',route:'#4c93ff'};
  const mx=w*.5,my=h*.72,L=(x1,y1,x2,y2,a,b)=>`<path d="M${x1} ${y1}L${x2} ${y2}" stroke="${C.r1}" stroke-width="${a}" stroke-linecap="round"/><path d="M${x1} ${y1}L${x2} ${y2}" stroke="${C.r2}" stroke-width="${b}" stroke-linecap="round"/>`;
  let s=`<rect width="${w}" height="${h}" fill="${C.bg}"/><path d="M${w*.72} 0L${w} 0L${w} ${h*.55}Q${w*.86} ${h*.4} ${w*.72} 0Z" fill="${C.sea}"/><rect x="${w*.08}" y="${h*.1}" width="${w*.26}" height="${h*.2}" rx="30" fill="${C.park}"/><rect x="${w*.1}" y="${h*.5}" width="${w*.18}" height="${h*.16}" rx="20" fill="${C.park}"/>`;
  for(let i=-6;i<12;i++)s+=L(-50,i*70,w+50,i*70-260,5,3);
  for(let i=-4;i<14;i++)s+=L(i*95,h+40,i*95+380,-40,5,3);
  s+=L(-40,my+h*.28,w+40,my-h*.55,14,10)+L(mx,my+h*.45,mx+10,-30,12,8);
  if(hasRoute)s+=`<path d="M${mx} ${my}L${mx+4} ${h*.28}Q${mx+6} ${h*.14} ${mx+60} ${h*.1}L${w+20} ${h*.02}" stroke="${C.route}" stroke-width="11" fill="none" stroke-linecap="round" stroke-linejoin="round" opacity=".95"/>`;
  s+=`<text x="${w*.12}" y="${h*.9}" fill="${C.txt}" font-size="17" font-family="Arial">La Grande Route de Faldouet</text><text x="${mx+16}" y="${h*.5}" fill="${C.txt}" font-size="17" font-family="Arial" transform="rotate(-90 ${mx+16} ${h*.5})">Rue du Parc</text><text x="${w*.78}" y="${h*.22}" fill="${C.seaT}" font-size="18" font-family="Arial" font-style="italic">Grouville Bay</text><text x="12" y="${h-10}" fill="${C.txt}" font-size="13" font-family="Arial">Stand-in map for the simulator</text>`;
  const old=document.getElementById('fakemap');if(old)old.remove();
  const n=document.createElementNS('http://www.w3.org/2000/svg','svg');n.id='fakemap';n.setAttribute('width',w);n.setAttribute('height',h);n.style.cssText='position:absolute;left:0;top:0;z-index:0';n.innerHTML=s;m.prepend(n);
}
function drive(dt){        // moves the car along the made-up route, four times faster than life so a trip takes a minute or two
  if(!route){sim.route=null;sim.routeFor=null;return}
  if(sim.routeFor!==route){sim.routeFor=route;sim.route=sim.pending;sim.along=0}      // a new route from the car page
  const c=sim.route;if(!c||sim.speedKmh<=0)return;
  sim.along+=sim.speedKmh/3.6*dt*4;let left=sim.along;
  for(let i=1;i<c.length;i++){const d=hav(c[i-1],c[i]);if(left<=d||i===c.length-1){const t=Math.min(1,left/(d||1));sim.lon=c[i-1][0]+(c[i][0]-c[i-1][0])*t;sim.lat=c[i-1][1]+(c[i][1]-c[i-1][1])*t;
    sim.heading=(Math.atan2((c[i][0]-c[i-1][0])*Math.cos(sim.lat*Math.PI/180),c[i][1]-c[i-1][1])*180/Math.PI+360)%360;return}left-=d}
}
let lastTick=performance.now();
function tick(){
  const now=performance.now(),dt=Math.max(0,Math.min(2,(now-lastTick)/1000));lastTick=now;
  try{drive(dt)}catch(e){}
  if(sim.music&&sim.playing&&sim.access)sim.posMs=(sim.posMs+dt*1000)%1800000;
  const t=TRACKS[sim.track],showMusic=sim.music&&sim.access;
  const call=sim.call?Object.assign({},sim.call,sim.access||sim.call.outgoing?{}:{name:''}):null;
  window.i3feed({soc:sim.soc,rangeKm:Math.round(sim.soc*1.52),speedKmh:sim.speedKmh,units:'Miles,CELSIUS',ambientC:12,cabinC:19,torqueNm:sim.speedKmh>0?35:0,tariffP:28,odoKm:41230,
    lat:sim.lat,lon:sim.lon,heading:sim.heading,mediaPermission:sim.access,
    media:showMusic?{title:t[0],artist:t[1],album:t[2],app:APPS[sim.app],playing:sim.playing,pos:sim.posMs,dur:1800000}:null,
    notes:sim.access?sim.notes:null,call:call,missed:sim.contacts?sim.missed:0,
    event:{title:'Dentist',where:'Gorey Dental Practice, Gorey Village, Jersey',begin:Date.now()+2*3600000}});
  const wantArt=showMusic?sim.track:-1;if(wantArt!==lastArt){lastArt=wantArt;window.i3art(showMusic?art(t[3]):'')}
  ['mapmsg','diag'].forEach(id=>{const e=document.getElementById(id);if(e)e.style.display='none'});
  try{drawMap()}catch(e){}
  post('state',{screen:home?'Home screen':(TITLES[PAGES[page]]||PAGES[page]),list:menu?menu.title:'',day:document.body.classList.contains('daymap'),route:!!route,call:sim.call?(sim.call.ongoing?'on':'ringing'):'',playing:sim.playing&&sim.music,music:sim.music,notes:sim.notes.length});
}
const ALERTS=[['WhatsApp','Sarah','Are you home for tea?'],['Messages','Jersey Water','Your bill is ready to view'],['Gmail','Parcel delivery','Arriving between 2 and 4 pm'],['WhatsApp','Tom','Running ten minutes late'],['Calendar','Dentist','Tomorrow at 9:15']];
let alertNo=0;
window.addEventListener('message',e=>{const d=e.data;if(!d||!d.i3cmd)return;
  try{switch(d.i3cmd){
    case 'knob':window.i3knob(d.dir);break;
    case 'press':window.i3press();break;
    case 'speed':sim.speedKmh=d.value*1.609344;break;
    case 'soc':sim.soc=d.value;break;
    case 'ring':if(!sim.call){sim.call={name:d.name||'Mum',text:'Mobile',number:'',outgoing:false,ongoing:false,since:Date.now()};say('The phone rings: '+(d.name||'Mum'))}break;
    case 'endcall':if(sim.call){sim.call=null;say('The other person ended the call')}break;
    case 'alert':{const a=ALERTS[alertNo++%ALERTS.length];sim.notes=[{app:a[0],title:a[1],text:a[2],when:Date.now(),key:'n'+alertNo,reply:a[0]==='WhatsApp'||a[0]==='Messages'}].concat(sim.notes).slice(0,8);say('New alert from '+a[0])}break;
    case 'clearalerts':sim.notes=[];break;
    case 'missed':sim.missed=d.on?sim.missed+1:0;say(d.on?'A call was missed':'Missed calls cleared');menuCache=null;break;
    case 'music':sim.music=d.on;sim.playing=d.on;say(d.on?'Something is playing':'Nothing is playing');break;
    case 'day':{const moved=window.__simTime(d.on);if(sim.call)sim.call.since+=moved;sim.notes.forEach(n=>{n.when+=moved});mapTheme()}break;
    case 'access':sim.access=d.on;say(d.on?'Notification access: allowed':'Notification access: not allowed (no music details, alerts or caller names)');break;
    case 'contacts':sim.contacts=d.on;menuCache=null;say(d.on?'Calls and contacts: allowed':'Calls and contacts: not allowed');break;
  }}catch(err){say('Simulator error: '+err.message)}
  tick();
});
window.__simTime(true);try{mapTheme()}catch(e){}
sim.notes=[{app:'WhatsApp',title:'Sarah',text:'Are you home for tea?',when:Date.now()-120000,key:'n0',reply:true}];alertNo=1;
setInterval(tick,500);tick();post('ready',{});
})();
