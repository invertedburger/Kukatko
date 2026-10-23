// Smoke-test the Oneplay API with your own account (O2 TV moved to Oneplay in 2025).
// Credentials: %USERPROFILE%\.kukatko.env with O2_USER= / O2_PASS= (your Oneplay login).
// Flow from github.com/waladir/plugin.video.oneplay. Prints a summary, never tokens or passwords.
// Run: node tools/probe.mjs
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';

const DEVICE_NAME = 'Kukátko PC'; // the app is "Kukátko"; a different name so the probe never removes the app's device
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0';
const env = Object.fromEntries(readFileSync(`${homedir()}/.kukatko.env`, 'utf8').split(/\r?\n/)
    .filter(l => l.includes('=') && !l.startsWith('#')).map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1)]));

let version = 'v1.11';
let token = null;
const step = s => console.log(`[${new Date().toTimeString().slice(0, 8)}] ${s}`);

// The API path carries a version; an old one answers 404, a live one 400 to an empty body.
async function findVersion() {
    for (let minor = 11; minor < 50; minor++) {
        const v = `v1.${String(minor).padStart(2, '0')}`;
        const r = await fetch(`https://http.cms.jyxo.cz/api/${v}/user.login.step`, { method: 'POST', body: '{}', headers: { 'content-type': 'application/json' } });
        if (r.status !== 404) { version = v; return; }
    }
}

/** POST an API call; async answers arrive over the websocket opened for this request. */
async function call(method, payload, extra = {}) {
    const clientId = randomUUID(), requestId = randomUUID();
    const ws = new WebSocket(`wss://ws.cms.jyxo.cz/websocket/${clientId}`);
    const messages = [];
    let wake = null;
    ws.onmessage = e => { messages.push(JSON.parse(e.data)); wake?.(); };
    const next = async () => {
        const deadline = Date.now() + 15000;
        while (!messages.length) {
            if (Date.now() > deadline) throw new Error(`${method}: websocket timeout`);
            await new Promise(r => { wake = r; setTimeout(r, 500); });
        }
        return messages.shift();
    };
    try {
        const serverId = (await next()).data.serverId;
        const body = {
            deviceInfo: { deviceType: 'web', appVersion: 'R11.33', deviceManufacturer: 'Unknown', deviceOs: 'Linux' },
            capabilities: { async: 'websockets' },
            context: { requestId, clientId, sessionId: serverId, serverId },
            payload, ...extra,
        };
        const headers = { 'content-type': 'application/json;charset=UTF-8', 'user-agent': UA, accept: '*/*' };
        if (token) headers.authorization = `Bearer ${token}`;
        const res = await fetch(`https://http.cms.jyxo.cz/api/${version}/${method}`, { method: 'POST', headers, body: JSON.stringify(body) });
        const text = await res.text();
        let data = text ? JSON.parse(text) : {};
        if (data.result?.status === 'OkAsync') {
            let msg;
            do { msg = await next(); } while (msg.response?.context?.requestId !== requestId);
            data = msg.response;
        }
        if (data.result?.status !== 'Ok') throw new Error(`${method}: HTTP ${res.status} ${data.result?.status} ${data.result?.message ?? text.slice(0, 200)}`);
        return data.data;
    } finally {
        ws.close();
    }
}

step('API version');
await findVersion();
console.log(`   ${version}`);

step('login');
let login = await call('user.login.step', { command: { schema: 'LoginWithCredentialsCommand', email: env.O2_USER, password: env.O2_PASS } });
if (login.step?.schema === 'ShowAccountChooserStep') {
    const accounts = login.step.groups.flatMap(g => g.accounts ?? []).filter(a => a.extId || a.isActive);
    console.log('   accounts:', accounts.map(a => `${a.name} (${a.extId ?? a.accountProvider})`).join(', '));
    login = await call('user.login.step', { command: { schema: 'LoginWithAccountCommand', accountId: accounts[0].accountId, authCode: login.step.authToken } });
}
token = login.step.bearerToken;
const user = login.step.currentUser ?? login.step.user?.loggedUser ?? {};
const deviceId = user.currentDevice?.id;
console.log(`   logged in, step ${login.step.schema}, device ${deviceId ? 'registered' : 'none'}`);

if (deviceId) {
    step(`devices: naming this one "${DEVICE_NAME}", removing older "${DEVICE_NAME}" entries`);
    await call('user.device.change', { id: deviceId, name: DEVICE_NAME });
    const screen = (await call('setting.display', { screen: 'devices' })).screen;
    const devices = screen.blocks.find(b => b.schema === 'SettingUserDevicesBlock')?.devices?.devices ?? [];
    for (const d of devices.filter(d => d.id !== deviceId && d.name === DEVICE_NAME)) {
        await call('user.device.remove', { criteria: { schema: 'UserDeviceIdCriteria', id: d.id } });
    }
    console.log(`   account devices: ${devices.map(d => d.name).join(', ')}`);
}

step('profile');
const profiles = (await call('user.profiles.display', { mode: 'change' })).availableProfiles.profiles;
console.log(`   profiles: ${profiles.map(p => p.profile.name).join(', ')}`);
const profileId = profiles[0].profile.id;
token = (await call('user.profile.select', { profileId })).bearerToken ?? token;

step('channels');
const channels = (await call('epg.channels.display', { profileId: String(profileId) })).channelList
    .filter(c => !c.upsell && !c.flags?.upsell).sort((a, b) => a.order - b.order);
console.log(`   ${channels.length} channels: ${channels.slice(0, 15).map(c => `${c.order} ${c.name}`).join(', ')} ...`);

step('now playing');
const now = new Date();
const epg = await call('epg.display', {
    criteria: {
        channelSetId: 'channel_list.1',
        viewport: { channelRange: { from: 0, to: 200 }, timeRange: { from: now.toISOString(), to: new Date(now.getTime() + 60000).toISOString() }, schema: 'EpgViewportAbsolute' },
    },
    requestedOutput: { channelList: 'none', datePicker: false, channelSets: false },
});
const onNow = epg.schedule.flatMap(c => (c.items ?? []).filter(i => new Date(i.startAt) <= now && new Date(i.endAt) > now).map(i => [c.channelId, i.title]));
console.log(`   ${onNow.length} programmes on air, e.g. ${onNow.slice(0, 3).map(([, t]) => t).join(' | ')}`);

const capabilities = { protocols: ['dash', 'hls'], drm: ['widevine'], altTransfer: 'Unicast', multipleAudio: false };
for (const ch of channels.slice(0, 5)) {
    const t0 = Date.now();
    try {
        const play = await call('content.play', { criteria: { schema: 'ContentCriteria', contentId: `channel.${ch.id}` }, startMode: 'start' },
            { playbackCapabilities: { ...capabilities, liveSpecificCapabilities: capabilities } });
        const assets = play.media?.stream?.assets ?? [];
        console.log(`   ${ch.name.padEnd(20)} ${String(Date.now() - t0).padStart(5)} ms  ${assets.map(a => a.protocol + (a.drm ? '+drm' : '')).join(', ') || 'no media'}`);
    } catch (e) {
        console.log(`   ${ch.name.padEnd(20)} ${e.message}`);
    }
}
// node tools/probe.mjs startover: can a programme that is on air now be played from its beginning?
if (process.argv.includes('startover')) {
    step('start over: programme on air now');
    for (const ch of channels.slice(0, 5)) {
        const row = epg.schedule.find(c => c.channelId === ch.id);
        const item = row?.items?.find(i => new Date(i.startAt) <= now && new Date(i.endAt) > now);
        const action = item?.actions?.[0];
        if (!item) { console.log(`   ${ch.name.padEnd(20)} nothing on air`); continue; }
        let verdict = `action ${action?.call ?? 'none'}`;
        if (action?.call === 'page.content.display') {
            try {
                const page = await call('page.content.display', action.params.payload);
                const header = page.layout?.blocks?.find(b => b.schema === 'ContentHeaderBlock');
                const main = header?.mainAction?.action;
                verdict += ` -> main ${main?.call ?? 'none'} "${header?.mainAction?.label ?? ''}"`;
                if (main?.call === 'content.play') {
                    const play = await call('content.play', main.params.payload, { playbackCapabilities: capabilities });
                    const assets = play.media?.stream?.assets ?? [];
                    verdict += ` -> ${assets.map(a => a.protocol + (a.drm ? '+drm' : '')).join(', ') || 'no media'}`;
                    verdict += `, control ${Object.keys(play.playerControl ?? {}).join('/')}`;
                }
            } catch (e) {
                verdict += ` -> ${e.message}`;
            }
        }
        console.log(`   ${ch.name.padEnd(20)} "${item.title}" ${verdict}`);
    }
    const ch = channels[0];
    const live = await call('content.play', { criteria: { schema: 'ContentCriteria', contentId: `channel.${ch.id}` }, startMode: 'start' },
        { playbackCapabilities: { ...capabilities, liveSpecificCapabilities: capabilities } });
    console.log(`   live ${ch.name} timeline: ${JSON.stringify(live.playerControl?.liveControl?.timeline ?? null).slice(0, 400)}`);
}
step('done');
