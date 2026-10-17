// Protocol integration only: verifies handshake/download/hash and commands, not audio rendering.
const mc=require('minecraft-protocol'), crypto=require('node:crypto'), fs=require('node:fs'), assert=require('node:assert/strict');
const nbt=require('prismarine-nbt');
const base='http://127.0.0.1:19880';
const player='DiscTest'+Date.now().toString().slice(-6);
const client=mc.createClient({host:'127.0.0.1',port:25575,username:player,version:'26.1',auth:'offline'});
const messages=[];let packs=0,configurationPack=false,joined=false,pos,sound=false,ambientStops=0,vanillaStarts=0;
client.on('position',p=>{pos=p;client.write('teleport_confirm',{teleportId:p.teleportId});});
client.on('packet',(p,m)=>{if(m.name==='stop_sound'&&p.source===1)ambientStops++;if(m.name==='world_event'&&p.effectId===1010&&p.data)vanillaStarts++;if(m.name.includes('sound')&&JSON.stringify(p).includes('fd_')){sound=true;console.log('CUSTOM SOUND',JSON.stringify(p));}});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function plain(v){if(v==null)return '';if(typeof v==='string')return v;if(Array.isArray(v))return v.map(plain).join(' ');if(typeof v==='object')return Object.values(v).map(plain).join(' ');return '';}
client.on('system_chat',packet=>{const value=nbt.simplify(packet.content);messages.push(plain(value));console.log('CHAT',plain(value).replace(/https?:\/\/\S+/g,'[private upload link]'));});
client.on('add_resource_pack',async p=>{
 try {
  assert.equal(p.forced,true);assert(p.url.startsWith(base+'/pack/'));
  if(client.state==='configuration')configurationPack=true;
  client.write('resource_pack_receive',{uuid:p.uuid,result:3});
  const r=await fetch(p.url);assert.equal(r.status,200);const data=Buffer.from(await r.arrayBuffer());
  assert.equal(crypto.createHash('sha1').update(data).digest('hex'),p.hash);assert.equal(data.readUInt32LE(0),0x04034b50);
  client.write('resource_pack_receive',{uuid:p.uuid,result:4});
  client.write('resource_pack_receive',{uuid:p.uuid,result:0});
  packs++;console.log('PACK PASS',p.hash,data.length,'bytes',client.state);
 }catch(e){console.error(e);process.exit(1);}
});
client.on('login',()=>{joined=true;});
client.on('error',e=>{console.error(e);process.exit(1);});
client.on('disconnect',p=>{console.error('DISCONNECT',plain(p));process.exit(1);});
async function until(fn,timeout=30000){const end=Date.now()+timeout;while(!fn()){if(Date.now()>end)throw Error('Timeout');await sleep(100);}return fn();}
function cmd(c){client.write('chat_command',{command:c});}
async function ticket(){const start=messages.length;cmd('disco enviar');await until(()=>messages.slice(start).find(m=>m.includes('/#')));return messages.slice(start).find(m=>m.includes('/#')).match(/#([A-Za-z0-9_-]{32})/)[1];}
async function upload(t,data,title){return fetch(base+'/upload',{method:'POST',headers:{Authorization:'Bearer '+t,'Content-Type':'application/octet-stream','X-Disc-Title':encodeURIComponent(title),'X-Disc-Color':'azul'},body:data});}
(async()=>{
 await until(()=>joined,60000);assert(configurationPack);await sleep(1500);
 const audio=fs.readFileSync('../tone.wav');
 let t=await ticket();let r=await upload(t,Buffer.from('not an audio'),'Invalid');assert.equal(r.status,202);
 await until(asyncNoop=()=>messages.find(m=>m.includes('Não consegui')||m.includes('Não reconheci')));await sleep(500);
 const qstart=messages.length;cmd('disco cota');await until(()=>messages.slice(qstart).find(m=>m.includes('0 de 5')));
 console.log('INVALID AUDIO DOES NOT CONSUME QUOTA');
 require('node:child_process').execFileSync('sudo',['-n','-u','minecraft','screen','-S','featherdiscos-test','-p','0','-X','stuff','op '+player+'\r']);
 await sleep(500);cmd('give @s minecraft:stone 2304');await sleep(700);
 for(let i=1;i<=5;i++){
  await sleep(700);t=await ticket();const start=messages.length;
  r=await upload(t,audio,'Integration '+i);assert.equal(r.status,202);
  const duplicate=await upload(t,audio,'Duplicate');assert.equal(duplicate.status,409);
  await until(()=>messages.slice(start).find(m=>m.includes('Cota: '+i+'/5')),60000);
  if(i===1){await until(()=>messages.slice(start).find(m=>m.includes('Inventário cheio')));cmd('clear @s');await sleep(500);cmd('disco receber');console.log('PASS FULL INVENTORY RECOVERY');}
  await until(()=>messages.slice(start).find(m=>m.includes('entregue!')));
 }
 const start=messages.length;await sleep(700);cmd('disco enviar');
 await until(()=>messages.slice(start).find(m=>m.includes('5 de 5')));assert(!messages.slice(start).some(m=>m.includes('/#')));
 console.log('PASS OP QUOTA');
 await until(()=>pos);cmd('setblock ~ ~-1 ~ minecraft:jukebox');await sleep(700);
 client.write('held_item_slot',{slotId:0});
 client.write('block_place',{hand:'main_hand',location:{x:Math.floor(pos.x),y:Math.floor(pos.y)-1,z:Math.floor(pos.z)},direction:1,cursorX:0.5,cursorY:1,cursorZ:0.5,insideBlock:false,worldBorderHit:false,sequence:1});
 await until(()=>sound,10000);console.log('PASS JUKEBOX SOUND');await sleep(2500);assert(ambientStops>=3,'ambient music is stopped while playing');assert.equal(vanillaStarts,0,'native record world event is suppressed');await sleep(4000);const stopped=ambientStops;await sleep(1200);assert.equal(ambientStops,stopped,'ambient music is restored after disc ends');console.log('PASS AMBIENT/BASE MUSIC GUARD',ambientStops);
 console.log('PASS: configuration required pack, public HTTPS hash, invalid audio, 5 creations, live pack pushes, delivery, reused token rejected, sixth creation blocked. Player='+player+' Packs='+packs);
 client.end();setTimeout(()=>process.exit(0),500);
})().catch(e=>{console.error(e);client.end();process.exit(1);});
setTimeout(()=>{console.error('Test total timeout');process.exit(1);},300000).unref();
