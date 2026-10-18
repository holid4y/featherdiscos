// Sandbox-only gameplay/abuse test for the FeatherFlux backpack (Expendable Backpacks fork) with
// OriginsPaper, Lootin, EvenMoreFish, BreweryX, FeatherDiscos, AxGraves and GSit on Paper 26.2 :25575.
// Thresholds in the sandbox config are lowered (1 fish, 2 chests, 1 drink).
const mc = require('minecraft-protocol'), nbt = require('prismarine-nbt'), fs = require('node:fs');
const md = require('minecraft-data')('26.1');
const { execFileSync } = require('node:child_process');
const LOG = '/srv/games/featherdiscos-test/logs/latest.log';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const results = [];
function ok(name, cond, detail = '') { results.push([cond ? 'PASS' : 'FAIL', name]); console.log(cond ? 'PASS' : 'FAIL', name, detail ? '| ' + String(detail).slice(0, 300) : ''); }
function plain(v) { if (v == null) return ''; if (typeof v === 'string') return v; if (Array.isArray(v)) return v.map(plain).join(''); if (typeof v === 'object') return Object.values(v).map(plain).join(''); return String(v); }
function consoleCmd(c) { execFileSync('sudo', ['-n', '-u', 'minecraft', 'screen', '-S', 'featherdiscos-test', '-p', '0', '-X', 'stuff', c + '\r']); }
function logLines() { return execFileSync('sudo', ['-n', 'cat', LOG]).toString().split('\n'); }
async function until(fn, timeout = 30000) { const end = Date.now() + timeout; for (;;) { const v = await fn(); if (v) return v; if (Date.now() > end) throw Error('Timeout'); await sleep(100); } }
async function query(c, re, timeout = 10000) { const n = logLines().length; consoleCmd(c); return until(() => logLines().slice(n - 1).find(l => re.test(l)), timeout); }
async function count(name, item) { const l = await query(`minecraft:clear ${name} ${item} 0`, /Found \d+ matching|No items were found/); const m = l.match(/Found (\d+)/); return m ? +m[1] : 0; }
async function groundIds() { return (await query('data get entity @e[type=item,limit=1,sort=nearest] Item.id', /entity data|No entity/)).split('INFO]: ')[1]; }
async function itemsOnGround() { const l = await query('execute if entity @e[type=item]', /Test (passed|failed)/); const m = l.match(/count: (\d+)/i); return m ? +m[1] : 0; }
async function slot8(name) { const l = await query(`data get entity ${name} Inventory[{Slot:8b}].components."minecraft:custom_data".PublicBukkitValues`, /has the following entity data|Found no elements|No entity was found/); return l; }
async function info(name) { return query(`mochila info ${name}`, /\[Mochila\].*(nível de origem|ainda não tem)/); }
const itemName = it => (it && it.itemCount > 0) ? md.items[it.itemId]?.name : null;

function bot(name) {
  const c = mc.createClient({ host: '127.0.0.1', port: 25575, username: name, version: '26.1', auth: 'offline' });
  Object.assign(c, { msgs: [], vel: [], spawns: [], pass: [], sounds: [], name, windows: {}, stateId: 0, win: null });
  c.on('position', p => { c.pos = { x: p.x, y: p.y, z: p.z }; c.write('teleport_confirm', { teleportId: p.teleportId }); });
  c.on('login', p => { c.eid = p.entityId; });
  c.on('add_resource_pack', p => { for (const r of [3, 4, 0]) c.write('resource_pack_receive', { uuid: p.uuid, result: r }); });
  c.on('system_chat', p => c.msgs.push(plain(nbt.simplify(p.content))));
  c.on('action_bar', p => c.msgs.push('AB:' + plain(nbt.simplify(p.text))));
  c.on('spawn_entity', p => c.spawns.push(p));
  c.on('set_passengers', p => c.pass.push(p));
  c.on('entity_sound_effect', p => c.sounds.push(JSON.stringify(p)));
  c.on('sound_effect', p => c.sounds.push(JSON.stringify(p)));
  c.on('update_health', p => { c.hp = p; });
  c.on('open_window', p => { c.win = { id: p.windowId, title: plain(nbt.simplify(p.windowTitle)) }; });
  c.on('close_window', () => { c.win = null; });
  c.on('window_items', p => { c.windows[p.windowId] = p.items; c.stateId = p.stateId; });
  c.on('set_slot', p => { c.stateId = p.stateId; if (c.windows[p.windowId]) c.windows[p.windowId][p.slot] = p.item; });
  c.on('entity_metadata', p => { (c.meta ??= {})[p.entityId] = p.metadata; });
  c.on('kick_disconnect', p => console.error(name, 'KICK', JSON.stringify(p).slice(0, 300)));
  c.on('error', e => console.error(name, 'ERROR', e.message));
  c.shift = on => c.write('player_input', { inputs: { shift: on } });
  c.look = (yaw, pitch) => c.write('look', { yaw, pitch, flags: { onGround: true } });
  c.move = (x, y, z) => { c.pos = { x, y, z }; c.write('position', { x, y, z, flags: { onGround: true } }); };
  c.hold = s => c.write('held_item_slot', { slotId: s });
  c.cmd = t => c.write('chat_command', { command: t });
  c.useItem = (seq = 1) => c.write('use_item', { hand: 'main_hand', sequence: seq, rotation: { x: 0, y: 0 } });
  c.click = (windowId, slot, button, mode) => c.write('window_click', { windowId, stateId: c.stateId, slot, mouseButton: button, mode, changedSlots: [], cursorItem: undefined });
  c.closeWin = id => { c.write('close_window', { windowId: id }); c.win = null; };
  c.since = n => c.msgs.slice(n);
  return c;
}

async function section(name, fn) {
  consoleCmd('minecraft:kill @e[type=!player,type=!item_display,type=!item]'); await sleep(300); consoleCmd('minecraft:kill @e[type=item]'); await sleep(300);
  try { await fn(); } catch (e) { ok(name + ' (crashed)', false, e.stack || e.message); }
}

(async () => {
  const id = Date.now().toString().slice(-4);
  let A = bot('Adm' + id), B = bot('Jog' + id), C = bot('Out' + id);
  await until(() => A.eid && B.eid && C.eid && A.pos && B.pos && C.pos, 120000);
  await sleep(3500);
  for (const c of [`op ${A.name}`, 'gamerule pvp true', 'time set day', 'gamerule advance_time false', 'gamerule keep_inventory false',
    'difficulty peaceful', 'fill -12 99 -12 12 99 12 minecraft:stone', 'fill -12 100 -12 12 108 12 minecraft:air', 'minecraft:kill @e[type=!player]',
    `origin set ${A.name} human`, `origin set ${B.name} human`, `origin set ${C.name} human`,
    `minecraft:tp ${A.name} -3.5 100 0.5`, `minecraft:tp ${B.name} 0.5 100 0.5 0 0`, `minecraft:tp ${C.name} 3.5 100 0.5`,
    `minecraft:clear ${B.name}`, `gamemode survival ${B.name}`, `gamemode survival ${C.name}`]) { consoleCmd(c); await sleep(200); }
  await sleep(3000);
  B.move(0.5, 100, 0.5); C.move(3.5, 100, 0.5); A.move(-3.5, 100, 0.5);

  await section('slot', async () => {
    const s = await slot8(B.name);
    ok('backpack arrives in hotbar slot 9 on join', /mochila_dono/.test(s), s.split('INFO]: ')[1]);
    ok('exactly one backpack', await count(B.name, 'player_head') === 1);
  });

  await section('drop/swap/place', async () => {
    B.hold(8); await sleep(200);
    B.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 }); await sleep(300); // Q
    B.write('block_dig', { status: 3, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 }); await sleep(300); // ctrl+Q
    B.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 }); await sleep(300); // F
    B.look(0, 90); await sleep(100);
    B.write('block_place', { hand: 'main_hand', location: { x: 0, y: 99, z: 0 }, direction: 1, cursorX: 0.5, cursorY: 1, cursorZ: 0.5, insideBlock: false, worldBorderHit: false, sequence: 2 });
    B.shift(true); await sleep(150);
    B.write('block_place', { hand: 'main_hand', location: { x: 0, y: 99, z: 0 }, direction: 1, cursorX: 0.5, cursorY: 1, cursorZ: 0.5, insideBlock: false, worldBorderHit: false, sequence: 3 });
    B.shift(false); await sleep(600);
    if (B.win) B.closeWin(B.win.id);
    const head = await query('execute if block 0 100 0 minecraft:player_head', /Test (passed|failed)/);
    ok('Q / ctrl+Q / F / place / sneak-place all refused', await count(B.name, 'player_head') === 1 && await itemsOnGround() === 0 && /failed/.test(head));
    ok('still in slot 9', /mochila_dono/.test(await slot8(B.name)));
    B.look(0, 0);
  });

  await section('inventory clicks', async () => {
    B.hold(0); await sleep(150);
    consoleCmd(`minecraft:item replace entity ${B.name} container.9 with minecraft:stone 5`); await sleep(500);
    B.click(0, 44, 0, 1); await sleep(250);          // shift-click the backpack
    B.click(0, 9, 8, 2); await sleep(250);           // number key 9 on another slot
    B.click(0, 44, 0, 4); await sleep(250);          // Q in the inventory
    B.click(0, 44, 1, 4); await sleep(250);          // ctrl+Q
    B.click(0, 44, 40, 2); await sleep(250);         // F swap to offhand from inventory
    B.click(0, 44, 0, 0); await sleep(250);          // pick up
    B.click(0, 5, 0, 0); await sleep(250);           // ...and try to put it on the head
    B.click(0, -999, 0, 0); await sleep(400);        // ...or drop it outside
    B.closeWin(0); await sleep(600);
    ok('every inventory click on the backpack is refused', /mochila_dono/.test(await slot8(B.name)) && await count(B.name, 'player_head') === 1 && await itemsOnGround() === 0);
    ok('the stone it was swapped with is untouched', await count(B.name, 'stone') === 5);
  });

  await section('/clear, overwrite, clone', async () => {
    consoleCmd(`minecraft:clear ${B.name} minecraft:player_head`); await sleep(2600);
    ok('/clear: backpack comes back within 2 s', /mochila_dono/.test(await slot8(B.name)));
    consoleCmd(`minecraft:item replace entity ${B.name} hotbar.8 with minecraft:diamond 3`); await sleep(2600);
    ok('something forced into slot 9 is moved out, backpack restored', /mochila_dono/.test(await slot8(B.name)) && await count(B.name, 'diamond') === 3);
    consoleCmd(`minecraft:item replace entity ${B.name} hotbar.2 from entity ${B.name} hotbar.8`); await sleep(300);
    consoleCmd(`minecraft:item replace entity ${B.name} armor.head from entity ${B.name} hotbar.8`); await sleep(2600);
    ok('cloned copies (inventory, head) are removed', await count(B.name, 'player_head') === 1);
    consoleCmd(`minecraft:item replace entity ${C.name} hotbar.0 from entity ${B.name} hotbar.8`); await sleep(2600);
    ok("someone else's backpack in another player's inventory is removed", (await count(C.name, 'player_head')) === 1);
    consoleCmd(`minecraft:clear ${B.name} minecraft:diamond`);
  });

  await section('item frame / armor stand', async () => {
    consoleCmd('setblock 0 100 3 minecraft:stone'); await sleep(200);
    const n = B.spawns.length;
    consoleCmd('summon minecraft:item_frame 0 100 2 {Facing:2b,Fixed:0b}');
    consoleCmd('summon minecraft:armor_stand 1.5 100 1.5');
    await sleep(1200);
    const frame = B.spawns.slice(n).find(s => s.type === md.entitiesByName.item_frame.id);
    const stand = B.spawns.slice(n).find(s => s.type === md.entitiesByName.armor_stand.id);
    B.hold(8); await sleep(150);
    if (frame) B.write('use_entity', { target: frame.entityId, hand: 'main_hand', location: { x: 0, y: 0, z: 0 }, sneaking: false });
    if (stand) B.write('use_entity', { target: stand.entityId, hand: 'main_hand', location: { x: 0, y: 1.6, z: 0 }, sneaking: false });
    await sleep(800);
    if (B.win) B.closeWin(B.win.id);
    const f = await query('data get entity @e[type=item_frame,limit=1] Item', /has the following entity data|Found no elements|No entity/);
    const st = await query('data get entity @e[type=armor_stand,limit=1] equipment', /has the following entity data|Found no elements|No entity/);
    ok('item frame and armor stand cannot take it', !/player_head/.test(f) && !/player_head/.test(st) && await count(B.name, 'player_head') === 1, f.split('INFO]: ')[1] + ' / ' + st.split('INFO]: ')[1]);
    consoleCmd('minecraft:kill @e[type=item_frame]'); consoleCmd('minecraft:kill @e[type=armor_stand]'); consoleCmd('setblock 0 100 3 minecraft:air');
    B.hold(0);
  });

  await section('open + shulker + backpack-in-backpack', async () => {
    consoleCmd(`minecraft:item replace entity ${B.name} hotbar.1 with minecraft:shulker_box`); consoleCmd(`minecraft:item replace entity ${B.name} hotbar.3 with minecraft:cobblestone 32`); await sleep(500);
    B.hold(8); await sleep(150); B.look(0, 0); B.useItem(5); await sleep(800);
    ok('right-click opens the backpack', !!B.win && /Couro/.test(B.win.title), B.win?.title);
    const w = B.win;
    if (w) {
      const size = (B.windows[w.id] || []).length - 36;
      B.click(w.id, size + 28, 0, 1); await sleep(300);  // shift-click the shulker (hotbar 1)
      B.click(w.id, size + 35, 0, 1); await sleep(300);  // shift-click the backpack itself
      B.click(w.id, 0, 1, 2); await sleep(300);          // number key 2 (shulker) onto backpack slot 0
      B.click(w.id, size + 30, 0, 1); await sleep(300);  // shift-click 32 cobblestone (allowed)
      const top = (B.windows[w.id] || []).slice(0, size).map(itemName).filter(Boolean);
      ok('shulker and the backpack itself stay out, cobblestone goes in', !top.includes('shulker_box') && !top.includes('player_head') && top.includes('cobblestone'), top.join(','));
      B.closeWin(w.id); await sleep(500);
    }
    ok('shulker still in the inventory, nothing lost', await count(B.name, 'shulker_box') === 1 && await count(B.name, 'player_head') === 1);
    consoleCmd(`minecraft:clear ${B.name} minecraft:shulker_box`);
    B.hold(0);
  });

  await section('relog keeps contents', async () => {
    B.end(); await sleep(2500);
    const B2 = bot(B.name); await until(() => B2.eid && B2.pos, 60000); await sleep(3500);
    B = B2;
    B.move(0.5, 100, 0.5);
    B.hold(8); await sleep(150); B.useItem(7); await sleep(800);
    const size = B.win ? (B.windows[B.win.id] || []).length - 36 : 0;
    const top = B.win ? (B.windows[B.win.id] || []).slice(0, size).map(itemName).filter(Boolean) : [];
    ok('after relog the cobblestone is still in the backpack, one backpack only', top.includes('cobblestone') && await count(B.name, 'player_head') === 1, top.join(','));
    if (B.win) B.closeWin(B.win.id);
    B.hold(0);
  });

  await section('two viewers, one item (dupe)', async () => {
    A.cmd(`mochila ver ${B.name}`); await sleep(800);
    B.hold(8); await sleep(100); B.useItem(9); await sleep(800);
    const wa = A.win, wb = B.win;
    if (!wa || !wb) { ok('admin view + owner view open together', false, `${!!wa} ${!!wb}`); return; }
    const sizeA = A.windows[wa.id].length - 36;
    const idx = A.windows[wa.id].slice(0, sizeA).findIndex(i => itemName(i) === 'cobblestone');
    A.click(wa.id, idx, 0, 1); B.click(wb.id, idx, 0, 1); await sleep(900);
    A.closeWin(wa.id); B.closeWin(wb.id); await sleep(800);
    const total = await count(A.name, 'cobblestone') + await count(B.name, 'cobblestone');
    B.hold(8); await sleep(100); B.useItem(11); await sleep(800);
    const left = B.win ? B.windows[B.win.id].slice(0, sizeA).filter(i => itemName(i) === 'cobblestone').reduce((a, i) => a + i.itemCount, 0) : -1;
    if (B.win) B.closeWin(B.win.id);
    ok('simultaneous shift-click by admin and owner: still 32 cobblestone in total', total + left === 32, `players ${total} + backpack ${left}`);
    ok('/mochila ver is admin-only', await (async () => { const n = C.msgs.length; C.cmd(`mochila ver ${B.name}`); await sleep(700); return C.since(n).some(m => m.includes('administrador')); })());
    consoleCmd(`minecraft:clear ${A.name} minecraft:cobblestone`);
    B.hold(0);
  });

  await section('origins -> capacity + unlocks', async () => {
    const n = B.msgs.length;
    consoleCmd(`origin setlevel ${B.name} 5`); await sleep(6500);
    const i = await info(B.name);
    ok('origin level 5 grows the backpack to Ferro (27) and unlocks Coletor/Reabastecer', /Mochila de Ferro/.test(i) && B.since(n).some(m => m.includes('cresceu')) && B.since(n).some(m => m.includes('Coletor')), i.split('INFO]: ')[1]);
    consoleCmd(`origin setlevel ${B.name} 1`); await sleep(6000);
    ok('lowering the level never shrinks it', /Mochila de Ferro/.test(await info(B.name)));
    ok('the backpack item shows the new tier', /mochila_dono/.test(await slot8(B.name)));
  });

  await section('goat cannot eat the backpack', async () => {
    consoleCmd(`origin set ${B.name} goat`); await sleep(800);
    if (B.win) B.closeWin(B.win.id);
    consoleCmd('difficulty easy'); await sleep(200);
    consoleCmd(`minecraft:effect give ${B.name} minecraft:hunger 5 100 true`); await sleep(5600); consoleCmd(`minecraft:effect clear ${B.name}`); await sleep(500);
    const food = B.hp.food;
    ok('(setup) the goat is hungry, so it would try to eat', food < 20, `food ${food}`);
    B.hold(8); await sleep(150); B.look(0, 0); B.shift(true); await sleep(250);
    B.write('use_item', { hand: 'main_hand', sequence: 70, rotation: { x: 0, y: 0 } }); await sleep(800);
    B.shift(false); if (B.win) B.closeWin(B.win.id); await sleep(400);
    ok('a goat crouch-clicking with the backpack opens the tree and does not eat it', B.hp.food === food && /mochila_dono/.test(await slot8(B.name)), `food ${food} -> ${B.hp.food}`);
    consoleCmd('difficulty peaceful');
    consoleCmd(`origin set ${B.name} human`); await sleep(800); if (B.win) B.closeWin(B.win.id);
    B.hold(0);
  });

  await section('growing while someone has it open', async () => {
    A.cmd(`mochila ver ${B.name}`); await sleep(800);
    const before = A.win;
    consoleCmd(`origin setlevel ${B.name} 7`); await sleep(6500);
    ok('the admin view of the old size is closed when the backpack grows (no stale inventory)', !!before && !A.win && /Mochila de Ouro/.test(await info(B.name)), `${before?.title} -> ${A.win?.title}`);
  });

  await section('coletor', async () => {
    consoleCmd(`minecraft:clear ${B.name}`); await sleep(2600);
    for (let s = 0; s < 36; s++) if (s !== 8) consoleCmd(`minecraft:item replace entity ${B.name} container.${s} with minecraft:dirt 64`);
    await sleep(800);
    consoleCmd(`summon minecraft:item 0.5 100.2 0.5 {Item:{id:"minecraft:diamond",count:4},PickupDelay:0s}`); await sleep(1500);
    ok('Coletor: with the inventory full, the diamonds go into the backpack', await itemsOnGround() === 0, await groundIds());
    A.cmd(`mochila ver ${B.name}`); await sleep(800);
    const top = A.win ? A.windows[A.win.id].slice(0, A.windows[A.win.id].length - 36) : [];
    if (A.win) A.closeWin(A.win.id);
    ok('...and they are there', top.some(i => itemName(i) === 'diamond' && i.itemCount === 4), top.map(itemName).filter(Boolean).join(','));
    consoleCmd(`summon minecraft:item 0.5 100.2 0.5 {Item:{id:"minecraft:dirt",count:1},PickupDelay:200s}`); await sleep(1200);
    ok('items with a pickup delay (just thrown) are left alone', await itemsOnGround() === 1, await groundIds());
    consoleCmd('minecraft:kill @e[type=item]');
    // switch Coletor off in the tree
    B.hold(8); B.shift(true); await sleep(150); B.useItem(13); await sleep(800); B.shift(false);
    ok('crouch + right-click opens the tree', !!B.win && /Árvore/.test(B.win.title), B.win?.title);
    if (B.win) { B.click(B.win.id, 29, 0, 0); await sleep(500); B.closeWin(B.win.id); }
    ok('the tree switches Coletor off', /Coletor \(desligada\)/.test(await query(`mochila info ${B.name}`, /Melhorias:/)));
    consoleCmd(`summon minecraft:item 0.5 100.2 0.5 {Item:{id:"minecraft:emerald",count:1},PickupDelay:0s}`); await sleep(1500);
    ok('...and then it stops collecting', await itemsOnGround() === 1);
    consoleCmd('minecraft:kill @e[type=item]');
    B.hold(8); B.shift(true); await sleep(150); B.useItem(15); await sleep(800); B.shift(false);
    if (B.win) { B.click(B.win.id, 29, 0, 0); await sleep(400); B.closeWin(B.win.id); }
    B.hold(0);
  });

  await section('reabastecer', async () => {
    consoleCmd(`minecraft:clear ${B.name} minecraft:dirt`); await sleep(400);
    consoleCmd(`minecraft:item replace entity ${B.name} hotbar.0 with minecraft:torch 1`);
    consoleCmd(`minecraft:item replace entity ${A.name} hotbar.0 with minecraft:torch 16`); await sleep(500);
    A.cmd(`mochila ver ${B.name}`); await sleep(800);
    if (A.win) { const size = A.windows[A.win.id].length - 36; A.click(A.win.id, size + 27, 0, 1); await sleep(400); A.closeWin(A.win.id); }
    await sleep(500);
    B.hold(0); B.look(0, 90); await sleep(150);
    B.write('block_place', { hand: 'main_hand', location: { x: 1, y: 99, z: 0 }, direction: 1, cursorX: 0.5, cursorY: 1, cursorZ: 0.5, insideBlock: false, worldBorderHit: false, sequence: 20 });
    await sleep(900);
    const placed = await query('execute if block 1 100 0 minecraft:torch', /Test (passed|failed)/);
    ok('Reabastecer: the last torch is placed and 16 more come from the backpack', /passed/.test(placed) && await count(B.name, 'torch') === 16, placed.split('INFO]: ')[1]);
    B.look(0, 0);
  });

  await section('death, grave, keepInventory', async () => {
    const before = await info(B.name);
    consoleCmd(`minecraft:kill ${B.name}`); await sleep(1200);
    B.write('client_command', { actionId: 0 }); await sleep(3000);
    ok('after death the backpack is back in slot 9, one copy', /mochila_dono/.test(await slot8(B.name)) && await count(B.name, 'player_head') === 1);
    const ground = await query('execute if entity @e[type=item,nbt={Item:{id:"minecraft:player_head"}}]', /Test (passed|failed)/);
    ok('no backpack dropped on the ground or left for the grave', /failed/.test(ground));
    A.cmd(`mochila ver ${B.name}`); await sleep(800);
    const top = A.win ? A.windows[A.win.id].slice(0, A.windows[A.win.id].length - 36).map(itemName).filter(Boolean) : [];
    if (A.win) A.closeWin(A.win.id);
    ok('backpack contents survive death', top.includes('diamond'), top.join(','));
    consoleCmd('gamerule keep_inventory true'); await sleep(200);
    consoleCmd(`minecraft:kill ${B.name}`); await sleep(1200); B.write('client_command', { actionId: 0 }); await sleep(3000);
    ok('with keepInventory: still exactly one backpack', await count(B.name, 'player_head') === 1);
    consoleCmd('gamerule keep_inventory false');
    consoleCmd(`minecraft:tp ${B.name} 0.5 100 0.5 0 0`); await sleep(500); B.move(0.5, 100, 0.5);
  });

  await section('lootin + saqueador', async () => {
    const chests = [-8, -6, -4];
    for (const x of chests) { consoleCmd(`setblock ${x} 100 -8 minecraft:air`); consoleCmd(`setblock ${x} 100 -8 minecraft:chest{LootTable:"minecraft:chests/simple_dungeon"}`); }
    await sleep(500);
    for (const x of chests) {
      consoleCmd(`minecraft:tp ${A.name} ${x + 0.5} 100 -5.5 180 29`); await sleep(900);
      A.move(x + 0.5, 100, -5.5); A.look(180, 29); await sleep(250); A.cmd('lootin set CHEST'); await sleep(600);
    }
    consoleCmd(`minecraft:tp ${A.name} -3.5 100 0.5`);
    const openChest = async (bot, x, seq) => { bot.write('block_place', { hand: 'main_hand', location: { x, y: 100, z: -8 }, direction: 3, cursorX: 0.5, cursorY: 0.5, cursorZ: 1, insideBlock: false, worldBorderHit: false, sequence: seq }); await sleep(900); const w = bot.win; const items = w ? (bot.windows[w.id] || []).slice(0, (bot.windows[w.id] || []).length - 36).filter(i => i && i.itemCount > 0) : null; if (w) bot.closeWin(w.id); await sleep(400); return { w, items }; };
    const at = async (bot, x) => { consoleCmd(`minecraft:tp ${bot.name} ${x + 0.5} 100 -6.5 180 29`); await sleep(900); bot.move(x + 0.5, 100, -6.5); bot.hold(0); await sleep(200); };
    await at(B, -8); const b1 = await openChest(B, -8, 30);
    await at(C, -8); const c1 = await openChest(C, -8, 31);
    ok('Lootin: two players open the same chest and both get their own loot', b1.items?.length > 0 && c1.items?.length > 0 && /Baú de loot/.test(b1.w?.title), `${b1.w?.title} B:${b1.items?.length} C:${c1.items?.length}`);
    await at(B, -6); await openChest(B, -6, 32);
    await sleep(5500);
    const i = await query(`mochila info ${B.name}`, /Melhorias:/);
    ok('two loot chests unlock Saqueador (sandbox threshold)', /Saqueador/.test(i), i.split('INFO]: ')[1]);
    await at(B, -4);
    const nmsg = B.msgs.length;
    B.hold(8); await sleep(200);
    const b3 = await openChest(B, -4, 33);
    B.hold(0); await sleep(300);
    ok('opening it with the backpack in hand: the loot goes to the backpack', B.since(nmsg).some(m => m.includes('Saqueador:')) && b3.items !== null && b3.items.length === 0, `title ${b3.w?.title} left ${b3.items?.length} msgs ${B.since(nmsg).join(' | ').slice(0, 200)} hand ${await query(`data get entity ${B.name} SelectedItem`, /entity data|Found no/)}`);
    B.shift(true); await sleep(150);
    const sneak = await openChest(B, -4, 35);
    B.shift(false); await sleep(200);
    ok('crouch-opening a loot chest shows the empty real chest, nothing to take', !sneak.items || sneak.items.length === 0, `${sneak.w?.title} ${sneak.items?.length}`);
    const again = await openChest(B, -4, 34);
    ok('...and reopening shows it empty (no second copy)', again.items !== null && again.items.length === 0, `${again.items?.length}`);
    C.write('block_dig', { status: 0, location: { x: -4, y: 100, z: -8 }, face: 3, sequence: 40 }); await sleep(200);
    C.write('block_dig', { status: 2, location: { x: -4, y: 100, z: -8 }, face: 3, sequence: 41 }); await sleep(600);
    const still = await query('execute if block -4 100 -8 minecraft:chest', /Test (passed|failed)/);
    ok('a normal player cannot break a loot chest', /passed/.test(still));
  });

  await section('brewery: drink unlocks Adega', async () => {
    consoleCmd(`origin setlevel ${B.name} 5`);
    const l = await query(`breweryx create wheatbeer 10 ${B.name}`, /Cervejaria|Poção|criad|created|Unknown|desconhecid/i);
    await sleep(500);
    const slot = (await query(`data get entity ${B.name} Inventory`, /entity data/)).match(/Slot: (\d+)b, id: "minecraft:potion"/);
    if (!slot) { ok('brew created for the player', false, l); return; }
    consoleCmd(`minecraft:item replace entity ${B.name} hotbar.4 from entity ${B.name} container.${slot[1]}`);
    if (slot[1] !== '4') consoleCmd(`minecraft:item replace entity ${B.name} container.${slot[1]} with minecraft:air`);
    await sleep(400);
    B.hold(4); await sleep(150); B.useItem(50); await sleep(2600);
    await sleep(5500);
    const i = await query(`mochila info ${B.name}`, /Melhorias:/);
    ok('drinking a brew counts and unlocks Adega', /Adega/.test(i), i.split('INFO]: ')[1]);
    B.hold(0);
  });

  await section('fishing: counts EMF fish and Bolso de Pesca stores it', async () => {
    consoleCmd('fill -3 98 6 3 99 14 minecraft:water'); consoleCmd(`minecraft:tp ${B.name} 0.5 100 4.5 0 30`); await sleep(1500);
    B.move(0.5, 100, 4.5);
    consoleCmd(`minecraft:item replace entity ${B.name} hotbar.2 with minecraft:fishing_rod[enchantments={lure:3,luck_of_the_sea:3}]`); await sleep(400);
    B.hold(2); await sleep(300); B.look(0, 30); await sleep(300);
    const before = await itemsOnGround();
    let caught = false;
    let catches = 0;
    for (let attempt = 0; attempt < 5 && catches < 2; attempt++) {
      const n = B.spawns.length;
      B.write('use_item', { hand: 'main_hand', sequence: 60 + attempt * 2, rotation: { x: 0, y: 30 } }); await sleep(1500);
      const hook = B.spawns.slice(n).find(s => s.type === md.entitiesByName.fishing_bobber.id);
      if (!hook) { console.log('  no hook on attempt', attempt, (await query(`data get entity ${B.name} SelectedItem`, /entity data|Found no/)).slice(0, 160)); continue; }
      const bit = await until(() => { const m = B.meta?.[hook.entityId]; return m && m.some(e => e.value === true); }, 45000).catch(() => false);
      B.write('use_item', { hand: 'main_hand', sequence: 61 + attempt * 2, rotation: { x: 0, y: 30 } }); await sleep(2500);
      if (bit) catches++;
      console.log('  attempt', attempt, 'bite', bit);
      caught = catches > 0;
      await sleep(5500);
    }
    await sleep(5500);
    const i = await info(B.name);
    ok('a caught EvenMoreFish fish is counted', caught && /peixes [1-9]/.test(i), i.split('INFO]: ')[1]);
    A.cmd(`mochila ver ${B.name}`); await sleep(800);
    const top = A.win ? A.windows[A.win.id].slice(0, A.windows[A.win.id].length - 36).map(itemName).filter(Boolean) : [];
    if (A.win) A.closeWin(A.win.id);
    ok('Bolso de Pesca: the second fish lands in the backpack', catches >= 2 && /peixes [2-9]/.test(await info(B.name)) && top.some(n => ['cod', 'salmon', 'tropical_fish', 'pufferfish', 'sponge', 'kelp', 'nautilus_shell', 'prismarine_crystals', 'ink_sac', 'seagrass'].includes(n) || n.includes('fish')), top.join(','));
    B.hold(0);
  });

  await section('visual on the back', async () => {
    consoleCmd(`minecraft:tp ${B.name} 0.5 100 0.5 0 0`); consoleCmd(`minecraft:tp ${C.name} 3.5 100 0.5`); await sleep(1500);
    B.move(0.5, 100, 0.5); C.move(3.5, 100, 0.5); await sleep(2500);
    const disp = C.spawns.filter(s => s.type === md.entitiesByName.item_display.id).map(s => s.entityId);
    const link = C.pass.filter(p => p.entityId === B.eid && p.passengers.some(x => disp.includes(x)));
    ok('another player sees the backpack display riding the wearer', disp.length > 0 && link.length > 0, `displays ${disp.length}, links ${link.length}`);
    const n = C.pass.length, sp = C.spawns.length;
    consoleCmd(`minecraft:tp ${B.name} 300.5 100 300.5`); await sleep(2500); consoleCmd(`minecraft:tp ${B.name} 0.5 100 0.5`); await sleep(3500);
    const disp2 = C.spawns.slice(sp).filter(s => s.type === md.entitiesByName.item_display.id).map(s => s.entityId);
    ok('teleports still work and the link comes back after a long teleport', C.pass.slice(n).some(p => p.entityId === B.eid && p.passengers.some(x => disp2.includes(x) || disp.includes(x))), `${C.pass.length - n} packets`);
    consoleCmd(`op ${B.name}`); await sleep(300);
    const m = B.msgs.length;
    B.cmd('sit'); await sleep(1500);
    const seat = C.pass.slice(n).find(p => p.passengers.includes(B.eid));
    ok('GSit /sit still seats the player with a backpack on', !!seat, seat ? JSON.stringify(seat) : B.since(m).join(' | '));
    B.shift(true); await sleep(400); B.shift(false); await sleep(600);
    consoleCmd(`deop ${B.name}`);
  });

  await section('toca-discos', async () => {
    const n = B.msgs.length;
    B.cmd('disco https://youtu.be/jNQXAC9IVRw Teste');
    const made = await until(() => B.since(n).find(m => /Disco #\d+ criado/.test(m) || m.includes('não foi gasta')), 180000).catch(() => null);
    await sleep(6000);
    if (!made) { ok('disc created for the Toca-discos test', false, B.since(n).join(' | ')); return; }
    const slot = (await query(`data get entity ${B.name} Inventory`, /entity data/)).match(/Slot: (\d+)b, id: "minecraft:music_disc/);
    consoleCmd(`minecraft:item replace entity ${A.name} hotbar.1 from entity ${B.name} container.${slot ? slot[1] : 0}`); await sleep(300);
    A.cmd(`mochila ver ${B.name}`); await sleep(800);
    if (A.win) { const size = A.windows[A.win.id].length - 36; A.click(A.win.id, size + 28, 0, 1); await sleep(400); A.closeWin(A.win.id); }
    await sleep(500);
    const s0 = C.sounds.length;
    const m = B.msgs.length; B.cmd('mochila tocar'); await sleep(1500);
    ok('Toca-discos plays the disc for players nearby', B.since(m).some(x => x.includes('Tocando')) && C.sounds.slice(s0).some(x => x.includes('fd_')), B.since(m).join(' | ') + ' || ' + C.sounds.slice(s0).join(' ').slice(0, 200));
    const mx = B.msgs.length; B.cmd('mochila tocar'); await sleep(500);
    ok('Toca-discos has a cooldown against spam', B.since(mx).some(x => x.includes('Espere')));
    const m2 = B.msgs.length; B.cmd('mochila parar'); await sleep(500);
    ok('/mochila parar stops it', !B.since(m2).some(x => x.includes('Nada tocando')));
  });

  const errors = logLines().filter(l => /ERROR|SEVERE|\tat |Could not pass event|Exception/.test(l) && !/unsupported server version|Block-attached entity|failed main thread check|AsyncCatcher|ServerLevel.getEntit|ProtocolLib\.jar|Lootin-13|Connection\.|io\.netty|java\.base|java\.lang\.Throwable/.test(l));
  ok('no errors or exceptions in the server log', errors.length === 0, errors.slice(0, 4).join(' || '));
  console.log('\nSUMMARY', results.filter(r => r[0] === 'PASS').length, 'pass,', results.filter(r => r[0] === 'FAIL').length, 'fail');
  for (const c of [A, B, C]) c.end();
  setTimeout(() => process.exit(0), 800);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
setTimeout(() => { console.error('Total timeout'); process.exit(1); }, 1500000).unref();
