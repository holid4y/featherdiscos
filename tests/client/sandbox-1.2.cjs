// Sandbox-only protocol test for OriginsPaper 1.4.2 + FeatherDiscos 1.2.0 (Paper 26.2 on :25575).
// Run from tests/client: node sandbox-1.2.cjs (needs the featherdiscos-test sandbox with OriginsPaper).
const mc = require('minecraft-protocol'), nbt = require('prismarine-nbt'), fs = require('node:fs');
const { execFileSync } = require('node:child_process');
const LOG = '/srv/games/featherdiscos-test/logs/latest.log';
const BASE = 'http://127.0.0.1:19880';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const results = [];
function ok(name, cond, detail = '') { results.push([cond ? 'PASS' : 'FAIL', name, detail]); console.log(cond ? 'PASS' : 'FAIL', name, detail); }
function plain(v) { if (v == null) return ''; if (typeof v === 'string') return v; if (Array.isArray(v)) return v.map(plain).join(''); if (typeof v === 'object') return Object.values(v).map(plain).join(''); return String(v); }
function consoleCmd(c) { execFileSync('sudo', ['-n', '-u', 'minecraft', 'screen', '-S', 'featherdiscos-test', '-p', '0', '-X', 'stuff', c + '\r']); }
function logLines() { return fs.readFileSync(LOG, 'utf8').split('\n'); }
async function until(fn, timeout = 30000) { const end = Date.now() + timeout; for (;;) { const v = fn(); if (v) return v; if (Date.now() > end) throw Error('Timeout'); await sleep(100); } }
async function query(c, re) { const n = logLines().length; consoleCmd(c); return until(() => logLines().slice(n - 1).find(l => re.test(l)), 10000); }
async function health(tag) { const l = await query(`data get entity @e[tag=${tag},limit=1] Health`, /entity data: [\d.]+f/); return parseFloat(l.match(/entity data: ([\d.]+)f/)[1]); }

function bot(name) {
  const c = mc.createClient({ host: '127.0.0.1', port: 25575, username: name, version: '26.1', auth: 'offline' });
  Object.assign(c, { msgs: [], vel: [], spawns: [], name });
  c.on('position', p => { c.pos = { x: p.x, y: p.y, z: p.z }; c.write('teleport_confirm', { teleportId: p.teleportId }); });
  c.on('login', p => { c.eid = p.entityId; });
  c.on('add_resource_pack', p => { c.write('resource_pack_receive', { uuid: p.uuid, result: 3 }); c.write('resource_pack_receive', { uuid: p.uuid, result: 4 }); c.write('resource_pack_receive', { uuid: p.uuid, result: 0 }); });
  c.on('system_chat', p => { const t = plain(nbt.simplify(p.content)); c.msgs.push(t); });
  c.on('action_bar', p => { c.msgs.push('AB:' + plain(nbt.simplify(p.text))); });
  c.on('entity_velocity', p => { if (p.entityId === c.eid) c.vel.push({ ...p.velocity, t: Date.now() }); });
  c.on('update_health', p => { c.hp = p; });
  c.on('spawn_entity', p => { c.spawns.push(p); });
  c.on('set_slot', p => { if (p.windowId === 0 || p.windowId === -2) c.lastSlot = p; });
  c.on('disconnect', p => console.error(name, 'DISCONNECT', JSON.stringify(p).slice(0, 300)));
  c.on('kick_disconnect', p => console.error(name, 'KICK', JSON.stringify(p).slice(0, 300)));
  c.on('error', e => console.error(name, 'ERROR', e.message));
  c.shift = on => c.write('player_input', { inputs: { shift: on } });
  c.look = (yaw, pitch) => c.write('look', { yaw, pitch, flags: { onGround: true } });
  c.move = (x, y, z, yaw = 0, pitch = 0) => { c.pos = { x, y, z }; c.write('position_look', { x, y, z, yaw, pitch, flags: { onGround: true } }); };
  c.cmd = t => c.write('chat_command', { command: t });
  c.since = n => c.msgs.slice(n);
  return c;
}
const vmag = v => Math.hypot(v.x, v.z);

(async () => {
  const id = Date.now().toString().slice(-5);
  const A = bot('Goat' + id), B = bot('Alvo' + id);
  await until(() => A.eid && B.eid && A.pos && B.pos, 90000);
  await sleep(3000);
  for (const c of [`op ${A.name}`, 'gamerule pvp true', 'time set day', 'gamerule advance_time false', 'difficulty easy', 'gamerule spawn_monsters false',
    'fill -8 99 -8 8 99 8 minecraft:stone', 'fill -8 100 -8 8 106 8 minecraft:air', 'kill @e[type=!player]',
    `origin set ${A.name} goat`, `origin set ${B.name} human`, `gamemode survival ${A.name}`, `gamemode survival ${B.name}`]) { consoleCmd(c); await sleep(250); }
  await sleep(1500);

  // ---- Goat graze: sneak + right-click air with dirt -----------------------------------------
  consoleCmd(`clear ${A.name}`); await sleep(200);
  consoleCmd(`tp ${A.name} 0.5 100 0.5 0 0`); consoleCmd(`tp ${B.name} 0.5 100 3.5 180 0`); await sleep(800);
  consoleCmd(`give ${A.name} minecraft:dirt 5`); consoleCmd(`effect give ${A.name} minecraft:hunger 2 255 true`); await sleep(2800);
  consoleCmd(`effect clear ${A.name}`); await sleep(600);
  A.write('held_item_slot', { slotId: 0 });
  const food0 = A.hp.food;
  A.look(0, 0); A.shift(true); await sleep(300);
  A.write('use_item', { hand: 'main_hand', sequence: 1, rotation: { x: 0, y: 0 } }); await sleep(800);
  const food1 = A.hp.food;
  ok('goat grazes dirt looking ahead', food1 === food0 + 1, `food ${food0} -> ${food1}`);
  A.look(0, 80); await sleep(200);
  A.write('use_item', { hand: 'main_hand', sequence: 2, rotation: { x: 0, y: 80 } }); await sleep(800);
  ok('goat ignores the sneak-bridging pose', A.hp.food === food1, `food ${food1} -> ${A.hp.food}`);
  A.look(0, 0); A.shift(false);
  const dirt = await query(`clear ${A.name} minecraft:dirt 0`, /Found \d+ matching item|No items were found/);
  ok('one dirt eaten', /Found 4 /.test(dirt), dirt.split('INFO]: ')[1]);
  consoleCmd(`give ${A.name} minecraft:ender_pearl 2`); await sleep(500);
  A.write('held_item_slot', { slotId: 1 }); A.shift(true); await sleep(300);
  const foodP = A.hp.food;
  A.write('use_item', { hand: 'main_hand', sequence: 3, rotation: { x: 0, y: -30 } }); await sleep(800);
  ok('goat does not eat an ender pearl', A.hp.food === foodP, `food ${foodP} -> ${A.hp.food}`);
  A.shift(false); A.write('held_item_slot', { slotId: 0 }); consoleCmd(`clear ${A.name}`); consoleCmd('kill @e[type=ender_pearl]'); await sleep(800);
  for (const [item, label] of [['minecraft:potion[potion_contents={potion:"minecraft:swiftness"}]', 'potion'], ['minecraft:carved_pumpkin', 'carved pumpkin']]) {
    consoleCmd(`clear ${A.name}`); await sleep(200); consoleCmd(`give ${A.name} ${item} 1`); await sleep(600);
    A.write('held_item_slot', { slotId: 0 }); A.shift(true); await sleep(250);
    const before = A.hp.food;
    A.write('use_item', { hand: 'main_hand', sequence: 4, rotation: { x: 0, y: 0 } }); await sleep(600);
    A.write('block_dig', { status: 5, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 });
    const left = await query(`clear ${A.name} ${item.split('[')[0]} 0`, /Found \d+ matching|No items/);
    ok(`goat does not eat a ${label}`, A.hp.food === before && /Found 1 /.test(left) || A.hp.food === before && label === 'carved pumpkin', `food ${before} -> ${A.hp.food}; ${left.split('INFO]: ')[1]}`);
    A.shift(false);
  }
  consoleCmd(`clear ${A.name}`); await sleep(1000);

  // ---- Goat: sprint hit has vanilla knockback now (Investida removed) -------------------------
  consoleCmd(`tp ${A.name} 0.5 100 0.5 0 0`); consoleCmd(`tp ${B.name} 0.5 100 2.5 180 0`); consoleCmd(`effect give ${B.name} minecraft:instant_health 1 10 true`); await sleep(1200);
  A.move(0.5, 100, 0.5); B.move(0.5, 100, 2.5, 180, 0);
  A.write('entity_action', { entityId: A.eid, actionId: 'start_sprinting', jumpBoost: 0 }); await sleep(200);
  B.vel.length = 0; A.write('attack', { entityId: B.eid }); A.write('arm_animation', { hand: 'main_hand' }); await sleep(700);
  const sprintKb = Math.max(0, ...B.vel.map(vmag));
  ok('sprint punch no longer launches (Ram removed)', sprintKb > 0 && sprintKb < 1.0, `horizontal ${sprintKb.toFixed(2)}`);
  A.write('entity_action', { entityId: A.eid, actionId: 'stop_sprinting', jumpBoost: 0 }); await sleep(1200);

  // ---- Goat headbutt knockback, Nv1 and Nv6 -------------------------------------------------
  async function headbutt(level) {
    consoleCmd(`origin setlevel ${A.name} ${level}`); consoleCmd(`effect give ${B.name} minecraft:instant_health 1 10 true`);
    consoleCmd(`tp ${A.name} 0.5 100 0.5 0 0`); consoleCmd(`tp ${B.name} 0.5 100 3.5 180 0`); await sleep(1500);
    A.move(0.5, 100, 0.5); B.move(0.5, 100, 3.5, 180, 0); A.look(0, 0);
    B.vel.length = 0; const hp0 = B.hp.health;
    A.shift(true); await sleep(150);
    A.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 }); await sleep(100);
    A.shift(false);
    for (let z = 1.1; z < 3.0; z += 0.6) { A.move(0.5, 100, z); await sleep(50); }
    await sleep(700);
    const best = B.vel.reduce((m, v) => vmag(v) > vmag(m) ? v : m, { x: 0, y: 0, z: 0 });
    return { best, dmg: hp0 - B.hp.health };
  }
  const n1 = await headbutt(1);
  ok('headbutt Nv1 shoves along the charge', n1.best.z > 0.9 && n1.best.z < 1.3 && n1.best.y > 0.3, `v=(${n1.best.x.toFixed(2)},${n1.best.y.toFixed(2)},${n1.best.z.toFixed(2)}) dmg=${n1.dmg.toFixed(1)}`);
  await sleep(3200);
  const n6 = await headbutt(6);
  ok('headbutt Nv6 shoves harder', n6.best.z > 1.2 && n6.best.z < 1.6, `v=(${n6.best.x.toFixed(2)},${n6.best.y.toFixed(2)},${n6.best.z.toFixed(2)}) dmg=${n6.dmg.toFixed(1)}`);

  // ---- Wolf: Soco Dilacerante -----------------------------------------------------------------
  consoleCmd(`origin set ${A.name} wolf`); consoleCmd(`origin setlevel ${A.name} 4`); await sleep(800);
  consoleCmd(`tp ${A.name} 0.5 100 0.5 0 0`); consoleCmd(`tp ${B.name} 6.5 100 6.5`); await sleep(800);
  A.move(0.5, 100, 0.5); A.look(0, 0);
  async function husk() {
    consoleCmd('kill @e[tag=bleed]'); await sleep(300);
    const n = A.spawns.length;
    consoleCmd('summon minecraft:husk 0.5 100 2.5 {NoAI:1b,Tags:["bleed"],PersistenceRequired:1b,equipment:{chest:{id:"minecraft:diamond_chestplate",count:1}}}');
    const s = await until(() => A.spawns.slice(n).find(p => p.type === 67), 10000);
    await sleep(600);
    return s.entityId;
  }
  let hid = await husk();
  A.shift(true); await sleep(200); A.write('attack', { entityId: hid }); A.write('arm_animation', { hand: 'main_hand' }); A.shift(false);
  await sleep(600); const l4a = await health('bleed'); await sleep(5500); const l4b = await health('bleed');
  ok('Nv4 crouch punch: no bleed yet', Math.abs(l4a - l4b) < 0.01, `${l4a} -> ${l4b}`);

  consoleCmd(`origin setlevel ${A.name} 5`); hid = await husk();
  const punch = 20 - l4a;
  let n = A.msgs.length;
  A.shift(true); await sleep(200); A.write('attack', { entityId: hid }); A.write('arm_animation', { hand: 'main_hand' });
  // Hit again during the bleed: refreshes hurt cooldown, must not swallow pulses, must say cooldown.
  await sleep(1050); A.write('attack', { entityId: hid }); A.write('arm_animation', { hand: 'main_hand' });
  A.shift(false);
  await sleep(6000);
  const h1 = await health('bleed');
  ok('Nv5 crouch punch announces the bleed', A.since(n).some(m => m.includes('Soco Dilacerante!')), A.since(n).filter(m => m.startsWith('AB:')).join(' | '));
  ok('second crouch punch is on cooldown', A.since(n).some(m => m.includes('em recarga')));
  const bleedLoss = 20 - h1 - 2 * punch;
  ok('bleed deals 5 through diamond armour despite melee', Math.abs(bleedLoss - 5) < 0.05, `punch ${punch.toFixed(2)} x2, 20 -> ${h1}, bleed ${bleedLoss.toFixed(2)}`);
  await sleep(1500);
  const h2 = await health('bleed');
  ok('bleed stops after 5 s', Math.abs(h2 - h1) < 0.01, `${h1} -> ${h2}`);
  consoleCmd('kill @e[tag=bleed]');

  // ---- FeatherDiscos ----------------------------------------------------------------------------
  n = A.msgs.length; A.cmd('disco cota'); await sleep(800);
  ok('op: unlimited quota message', A.since(n).some(m => m.includes('sem limite semanal')), A.since(n).join(' | '));
  n = A.msgs.length; A.cmd('disco https://open.spotify.com/track/6BWwOL0DEK1aLaDjyipjZj?si=x');
  const made = await until(() => A.since(n).find(m => /Disco #\d+ criado/.test(m) || m.includes('Sua cota não foi gasta') || m.includes('Não foi possível')), 240000);
  ok('spotify link creates a disc (op, no limit)', /criado.*Sem limite/.test(made), made);
  const discId = +(made.match(/Disco #(\d+)/) || [0, 0])[1];
  await sleep(1500);
  n = A.msgs.length; A.cmd('disco meus'); await sleep(800);
  ok('/disco meus lists with buttons', A.since(n).some(m => m.includes('#' + discId) && m.includes('[pegar]')), A.since(n).slice(-2).join(' | '));
  n = A.msgs.length; A.cmd('disco pegar ' + discId); await sleep(800);
  ok('/disco pegar gives a copy', A.since(n).some(m => m.includes('no seu inventário')), A.since(n).join(' | '));
  await sleep(3200);
  n = A.msgs.length; A.cmd('disco receber #' + discId); await sleep(800);
  ok('/disco receber <id> also gives a copy', A.since(n).some(m => m.includes('no seu inventário')), A.since(n).join(' | '));
  n = B.msgs.length; B.cmd('disco pegar ' + discId); await sleep(800);
  ok('non-creator cannot take someone else\'s disc', B.since(n).some(m => m.includes('só pode pegar')), B.since(n).join(' | '));
  n = B.msgs.length; B.cmd('disco cota'); await sleep(800);
  ok('player still has the weekly limit', B.since(n).some(m => / de 5 /.test(m)), B.since(n).join(' | '));

  n = B.msgs.length; B.cmd('disco enviar');
  const linkMsg = await until(() => B.since(n).find(m => m.includes('/#')) || null, 10000).catch(() => null);
  const ticketMatch = B.since(n).join(' ').match(/#([A-Za-z0-9_-]{32})/);
  // The ticket is in the click event, not the text: read it from the raw component instead.
  let ticket = ticketMatch && ticketMatch[1];
  if (!ticket) { ok('upload ticket issued', false, B.since(n).join(' | ')); }
  else {
    const bad = await fetch(BASE + '/link', { method: 'POST', headers: { Authorization: 'Bearer ' + ticket, 'Content-Type': 'application/json' }, body: JSON.stringify({ url: 'https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy', title: '', color: 'azul' }) });
    ok('/link accepts the request (album)', bad.status === 202, String(bad.status));
    const refused = await until(() => B.since(n).find(m => m.includes('álbum')), 120000).catch(() => null);
    ok('album link refused with a clear reason', !!refused, refused || B.since(n).join(' | '));
  }
  n = B.msgs.length; B.cmd('disco enviar'); await sleep(1000);
  ticket = (B.since(n).join(' ').match(/#([A-Za-z0-9_-]{32})/) || [])[1];
  if (ticket) {
    const r = await fetch(BASE + '/link', { method: 'POST', headers: { Authorization: 'Bearer ' + ticket, 'Content-Type': 'application/json' }, body: JSON.stringify({ url: 'https://youtu.be/jNQXAC9IVRw', title: '', color: 'azul' }) });
    ok('/link with a YouTube URL accepted', r.status === 202, `${r.status} ${await r.text()}`);
    const done = await until(() => B.since(n).find(m => /Disco #\d+ criado/.test(m) || m.includes('cota não foi gasta')), 240000).catch(() => null);
    ok('YouTube link via page creates a disc with quota', !!done && done.includes('Cota: 1/5'), done || B.since(n).join(' | '));
  }

  n = A.msgs.length; A.write('held_item_slot', { slotId: 8 }); await sleep(200); A.cmd('disco apagar'); await sleep(800);
  ok('/disco apagar without a held disc lists discs', A.since(n).some(m => m.includes('Segure o disco')) && A.since(n).some(m => m.includes('[apagar]')), A.since(n).slice(0, 3).join(' | '));
  const slot = await query(`clear ${A.name} minecraft:dirt 0`, /Found|No items/); // just to sync
  A.write('held_item_slot', { slotId: 0 }); await sleep(300);
  n = A.msgs.length; A.cmd('disco apagar'); await sleep(800);
  ok('/disco apagar with the disc in hand asks to confirm that disc', A.since(n).some(m => m.includes('Apagar o disco #' + discId) && m.includes('[CONFIRMAR]')), A.since(n).join(' | '));
  n = A.msgs.length; A.cmd(`disco apagar ${discId} confirmar`);
  const del = await until(() => A.since(n).find(m => m.includes('apagado')), 60000).catch(() => null);
  ok('/disco apagar <id> confirmar deletes', !!del, del || A.since(n).join(' | '));
  n = B.msgs.length; B.cmd('disco apagar 1'); await sleep(800);
  ok('players cannot delete', B.since(n).some(m => m.includes('administrador')), B.since(n).join(' | '));

  n = B.msgs.length; B.cmd('disco enviar'); await sleep(1000);
  ticket = (B.since(n).join(' ').match(/#([A-Za-z0-9_-]{32})/) || [])[1];
  B.end(); await sleep(1500);
  if (ticket) {
    const r = await fetch(BASE + '/link', { method: 'POST', headers: { Authorization: 'Bearer ' + ticket, 'Content-Type': 'application/json' }, body: JSON.stringify({ url: 'https://youtu.be/jNQXAC9IVRw', title: '', color: 'azul' }) });
    const text = await r.text();
    ok('page shows the real reason (player offline)', r.status === 400 && text.includes('Entre no servidor'), `${r.status} ${text}`);
  }
  const errors = logLines().filter(l => /ERROR|SEVERE|\tat |Could not pass event/.test(l) && /Origins|FeatherDiscos|dev\.originspaper|featherflux/.test(l));
  ok('no plugin errors in sandbox log', errors.length === 0, errors.slice(0, 3).join(' || '));
  console.log('\nSUMMARY', results.filter(r => r[0] === 'PASS').length, 'pass,', results.filter(r => r[0] === 'FAIL').length, 'fail');
  A.end(); setTimeout(() => process.exit(0), 500);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
setTimeout(() => { console.error('Total timeout'); process.exit(1); }, 900000).unref();
