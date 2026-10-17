const mc=require('minecraft-protocol'),nbt=require('prismarine-nbt'),fs=require('node:fs'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const name='Offline'+Date.now().toString().slice(-6), messages=[];
let client;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const plain=v=>v==null?'':typeof v==='string'?v:Array.isArray(v)?v.map(plain).join(' '):typeof v==='object'?Object.values(v).map(plain).join(' '):'';
async function until(fn,timeout=90000){const end=Date.now()+timeout;while(!fn()){if(Date.now()>end)throw Error('Timeout');await sleep(150);}return fn();}
async function connect(){let joined=false;const c=mc.createClient({host:'127.0.0.1',port:25575,username:name,version:'26.1',auth:'offline'});client=c;
 c.on('system_chat',p=>{const t=plain(nbt.simplify(p.content));messages.push(t);console.log(t);});
 c.on('add_resource_pack',async p=>{try{c.write('resource_pack_receive',{uuid:p.uuid,result:3});const r=await fetch(p.url),b=Buffer.from(await r.arrayBuffer());assert.equal(r.status,200);assert.equal(crypto.createHash('sha1').update(b).digest('hex'),p.hash);c.write('resource_pack_receive',{uuid:p.uuid,result:4});c.write('resource_pack_receive',{uuid:p.uuid,result:0});}catch(e){console.error(e);process.exit(1);}});
 c.on('login',()=>joined=true);c.on('error',e=>{console.error(e);process.exit(1);});await until(()=>joined);
}
(async()=>{
 await connect();await sleep(1000);client.write('chat_command',{command:'disco https://www.youtube.com/watch?v=jNQXAC9IVRw YouTube teste'});
 await until(()=>messages.some(m=>m.includes('Áudio na fila')));client.end();
 await until(()=>fs.readFileSync('/srv/games/featherdiscos-test/plugins/FeatherDiscos/discos.yml','utf8').includes('criador-nome: '+name));
 await sleep(500);await connect();await until(()=>messages.some(m=>m.includes('entregue!')));client.write('chat_command',{command:'disco cota'});await until(()=>messages.some(m=>m.includes('1 de 5')));
 console.log('PASS: YouTube download/conversion as minecraft, offline creation, reconnect pack and pending delivery');client.end();setTimeout(()=>process.exit(0),500);
})().catch(e=>{console.error(e);client.end();process.exit(1);});setTimeout(()=>process.exit(2),180000).unref();
