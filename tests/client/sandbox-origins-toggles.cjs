// Sandbox-only test for OriginsPaper 1.5.1 effect switches ("Minha Origem" → Visão noturna /
// Revelar criaturas) on Paper 26.2 :25575. Run with the sandbox up: node sandbox-origins-toggles.cjs
const mc = require('minecraft-protocol'), nbt = require('prismarine-nbt');
const md = require('minecraft-data')('26.1');
const { execFileSync } = require('node:child_process');
const LOG = '/srv/games/featherdiscos-test/logs/latest.log';
const NV = md.effectsByName.NightVision.id;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const results = [];
function ok(name, cond, detail = '') { results.push([cond ? 'PASS' : 'FAIL', name]); console.log(cond ? 'PASS' : 'FAIL', name, detail ? '| ' + String(detail).slice(0, 300) : ''); }
function plain(v) { if (v == null) return ''; if (typeof v === 'string') return v; if (Array.isArray(v)) return v.map(plain).join(''); if (typeof v === 'object') return Object.values(v).map(plain).join(''); return String(v); }
function consoleCmd(c) { execFileSync('sudo', ['-n', '-u', 'minecraft', 'screen', '-S', 'featherdiscos-test', '-p', '0', '-X', 'stuff', c + '\r']); }
function logLines() { return execFileSync('sudo', ['-n', 'cat', LOG]).toString().split('\n'); }
async function until(fn, timeout = 30000) { const end = Date.now() + timeout; for (;;) { const v = await fn(); if (v) return v; if (Date.now() > end) throw Error('Timeout'); await sleep(100); } }
async function query(c, re, timeout = 10000) { const n = logLines().length; consoleCmd(c); return until(() => logLines().slice(n - 1).find(l => re.test(l)), timeout); }
const itemName = it => (it && it.itemCount > 0) ? md.items[it.itemId]?.name : null;

function bot(name) {
  const c = mc.createClient({ host: '127.0.0.1', port: 25575, username: name, version: '26.1', auth: 'offline' });
  Object.assign(c, { msgs: [], effects: [], meta: {}, windows: {}, stateId: 0, win: null, spawned: {} });
  c.on('position', p => { c.pos = { x: p.x, y: p.y, z: p.z }; c.write('teleport_confirm', { teleportId: p.teleportId }); });
  c.on('login', p => { c.eid = p.entityId; });
  c.on('add_resource_pack', p => { for (const r of [3, 4, 0]) c.write('resource_pack_receive', { uuid: p.uuid, result: r }); });
  c.on('system_chat', p => c.msgs.push(plain(nbt.simplify(p.content))));
  c.on('action_bar', p => c.msgs.push('AB:' + plain(nbt.simplify(p.text))));
  c.on('entity_effect', p => { if (p.entityId === c.eid) c.effects.push(['add', p.effectId, p.duration, Date.now()]); });
  c.on('remove_entity_effect', p => { if (p.entityId === c.eid) c.effects.push(['remove', p.effectId, 0, Date.now()]); });
  c.on('spawn_entity', p => { c.spawned[p.entityId] = md.entities[p.type]?.name; });
  c.on('entity_metadata', p => { for (const m of p.metadata) if (m.key === 0) (c.meta[p.entityId] ??= []).push([m.value, Date.now()]); });
  c.on('open_window', p => { c.win = { id: p.windowId, title: plain(nbt.simplify(p.windowTitle)) }; });
  c.on('close_window', () => { c.win = null; });
  c.on('window_items', p => { c.windows[p.windowId] = p.items; c.stateId = p.stateId; });
  c.on('set_slot', p => { c.stateId = p.stateId; if (c.windows[p.windowId]) c.windows[p.windowId][p.slot] = p.item; });
  c.on('kick_disconnect', p => console.error(name, 'KICK', JSON.stringify(p).slice(0, 300)));
  c.on('error', e => console.error(name, 'ERROR', e.message));
  c.cmd = t => c.write('chat_command', { command: t });
  c.click = (windowId, slot) => c.write('window_click', { windowId, stateId: c.stateId, slot, mouseButton: 0, mode: 0, changedSlots: [], cursorItem: undefined });
  c.closeWin = id => { c.write('close_window', { windowId: id }); c.win = null; };
  return c;
}
async function join(name) { const c = bot(name); await until(() => c.pos, 60000); await sleep(4000); return c; }
const nvState = c => { const last = c.effects.filter(e => e[1] === NV).at(-1); return last ? last[0] : 'none'; };
const effectsSince = (c, t) => c.effects.filter(e => e[1] === NV && e[3] >= t);
async function openOrigin(c) { c.win = null; c.cmd('origin'); await until(() => c.win && /Minha Origem/.test(c.win.title) && c.windows[c.win.id], 10000); await sleep(300); return c.win.id; }
const slot = (c, id, s) => itemName(c.windows[id]?.[s]);
async function pig(c) {
  consoleCmd('minecraft:kill @e[type=pig]');
  await sleep(500);
  const before = new Set(Object.keys(c.spawned));
  consoleCmd(`execute at ${c.username} run summon minecraft:pig ~3 ~ ~ {NoAI:1b}`);
  return until(() => Object.entries(c.spawned).find(([id, t]) => t === 'pig' && !before.has(id))?.[0], 10000).then(Number);
}
const glowing = (c, eid, since) => (c.meta[eid] || []).filter(([, t]) => t >= since).some(([v]) => v & 0x40);

(async () => {
  const name = 'ToggleBot';
  let c = await join(name);
  await query(`origin set ${name} wolf`, /Lobo|wolf|definid|selected origin/i);
  await sleep(2500);
  ok('lobo: visão noturna aplicada', nvState(c) === 'add', JSON.stringify(c.effects.slice(-3)));

  let p = await pig(c); let t = Date.now(); await sleep(4500);
  ok('lobo: porco brilha com revelar ligado', glowing(c, p, t), JSON.stringify(c.meta[p]));

  let w = await openOrigin(c);
  ok('menu: botão visão noturna no 47', slot(c, w, 47) === 'golden_carrot', slot(c, w, 47));
  ok('menu: botão revelar no 51', slot(c, w, 51) === 'glow_ink_sac', slot(c, w, 51));

  t = Date.now(); c.click(w, 47); await sleep(1500);
  ok('desligar visão noturna remove o efeito', effectsSince(c, t).some(e => e[0] === 'remove'), JSON.stringify(effectsSince(c, t)));
  ok('botão vira cinza', slot(c, w, 47) === 'gray_dye', slot(c, w, 47));
  ok('aviso na action bar', c.msgs.some(m => /✘ Visão noturna/.test(m)), c.msgs.slice(-3));
  c.click(w, 51); await sleep(800);
  ok('revelar vira cinza', slot(c, w, 51) === 'gray_dye', slot(c, w, 51));
  c.click(w, 20); await sleep(300);
  ok('clicar em habilidade não faz nada', slot(c, w, 47) === 'gray_dye' && slot(c, w, 51) === 'gray_dye');
  c.closeWin(w);

  t = Date.now(); await sleep(3500);
  ok('visão noturna não volta sozinha', !effectsSince(c, t).some(e => e[0] === 'add'), JSON.stringify(effectsSince(c, t)));
  p = await pig(c); t = Date.now(); await sleep(4500);
  ok('porco não brilha com revelar desligado', !glowing(c, p, t), JSON.stringify(c.meta[p]));

  t = Date.now(); consoleCmd(`minecraft:effect give ${name} minecraft:night_vision 60 0`); await sleep(3500);
  ok('poção de visão noturna do jogador é mantida', effectsSince(c, t).some(e => e[0] === 'add') && !effectsSince(c, t).some(e => e[0] === 'remove'), JSON.stringify(effectsSince(c, t)));
  consoleCmd(`minecraft:effect clear ${name} minecraft:night_vision`); await sleep(800);

  c.end(); await sleep(2500);
  c = await join(name);
  ok('relog: visão noturna continua desligada', nvState(c) !== 'add', JSON.stringify(c.effects));
  w = await openOrigin(c);
  ok('relog: botões continuam desligados', slot(c, w, 47) === 'gray_dye' && slot(c, w, 51) === 'gray_dye', [slot(c, w, 47), slot(c, w, 51)]);
  t = Date.now(); c.click(w, 47); await sleep(1500);
  ok('religar visão noturna aplica o efeito', effectsSince(c, t).some(e => e[0] === 'add'), JSON.stringify(effectsSince(c, t)));
  c.click(w, 51); await sleep(800);
  ok('religar revelar', slot(c, w, 51) === 'glow_ink_sac', slot(c, w, 51));
  c.closeWin(w);
  p = await pig(c); t = Date.now(); await sleep(4500);
  ok('porco volta a brilhar', glowing(c, p, t), JSON.stringify(c.meta[p]));

  await query(`origin set ${name} human`, /Humano|human|definid|selected origin/i); await sleep(1500);
  w = await openOrigin(c);
  ok('humano: sem botões', !['golden_carrot', 'glow_ink_sac', 'gray_dye'].includes(slot(c, w, 47)) && !['golden_carrot', 'glow_ink_sac', 'gray_dye'].includes(slot(c, w, 51)), [slot(c, w, 47), slot(c, w, 51)]);
  c.closeWin(w);
  ok('humano: sem visão noturna', nvState(c) !== 'add', JSON.stringify(c.effects.slice(-2)));

  await query(`origin set ${name} otter`, /Lontra|otter|definid|selected origin/i); await sleep(1500);
  w = await openOrigin(c);
  ok('lontra: só o botão de visão noturna', slot(c, w, 47) === 'golden_carrot' && !['glow_ink_sac', 'gray_dye'].includes(slot(c, w, 51)), [slot(c, w, 47), slot(c, w, 51)]);
  c.closeWin(w);

  const errors = logLines().filter(l => /Exception|ERROR/.test(l) && !/moved wrongly|running an unsupported server version/.test(l));
  ok('sem exceções no log', errors.length === 0, errors.slice(0, 3).join(' / '));
  c.end();
  const failed = results.filter(r => r[0] === 'FAIL').length;
  console.log(`\n${results.length - failed}/${results.length} PASS`);
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
